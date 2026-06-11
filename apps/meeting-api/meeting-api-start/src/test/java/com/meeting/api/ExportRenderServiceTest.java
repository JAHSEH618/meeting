package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.export.ExportRenderService;
import com.meeting.api.app.export.ExportRenderService.ExportJobMessage;
import com.meeting.api.app.export.ExportRenderService.RenderOutcome;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportGatewayRegistry;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportJobCompletedEvent;
import com.meeting.api.domain.export.ExportJobRepository;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.meeting.api.domain.export.MeetingSnapshotPort;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportRenderServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T10:00:00Z");

    private InMemoryExports exportRepo;
    private InMemoryMeetingFiles meetingFileRepo;
    private StubSnapshotPort snapshotPort;
    private StubStorage storage;
    private RecordingPublisher publisher;
    private ExportRenderService service;

    @BeforeEach
    void setUp() {
        exportRepo = new InMemoryExports();
        meetingFileRepo = new InMemoryMeetingFiles();
        snapshotPort = new StubSnapshotPort();
        storage = new StubStorage();
        publisher = new RecordingPublisher();
        ExportGatewayRegistry registry = new ExportGatewayRegistry(List.of(
            new StubGateway(ExportFormat.MARKDOWN, "MARKDOWN_BODY".getBytes())
        ));
        service = new ExportRenderService(
            TenantScopedTransaction.immediate(),
            exportRepo, meetingFileRepo, snapshotPort, registry, storage, publisher,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            "meeting-exports",
            24 * 3600L
        );
    }

    @Test
    void happyPathTransitionsToSucceededAndPersistsFile() {
        exportRepo.put(jobAt(ExportStatus.QUEUED));
        snapshotPort.set(emptySnapshot());

        RenderOutcome outcome = service.render(messageFor("exp_01"));

        assertThat(outcome.finalStatus()).isEqualTo(ExportStatus.SUCCEEDED);
        assertThat(outcome.fileId()).startsWith("mf_");

        ExportJob persisted = exportRepo.findById("tenant_01", "exp_01").orElseThrow();
        assertThat(persisted.status()).isEqualTo(ExportStatus.SUCCEEDED);
        assertThat(persisted.fileHash()).isNotBlank();
        assertThat(persisted.downloadExpiresAt()).isNotNull();

        assertThat(meetingFileRepo.byId("tenant_01", outcome.fileId())).isPresent();
        assertThat(storage.uploaded)
            .singleElement()
            .satisfies(s -> {
                assertThat(s.bucket()).isEqualTo("meeting-exports");
                assertThat(s.objectKey()).contains("tenant/tenant_01/meeting/mtg_01/export/exp_01/");
            });

        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0)).isInstanceOf(ExportJobCompletedEvent.class);
        ExportJobCompletedEvent evt = (ExportJobCompletedEvent) publisher.events.get(0);
        assertThat(evt.status()).isEqualTo(ExportStatus.SUCCEEDED);
    }

    @Test
    void staleSnapshotMarksFailedAndRethrows() {
        exportRepo.put(jobAt(ExportStatus.QUEUED));
        // no snapshot configured → port returns empty → ExportInputInvalidException
        assertThatThrownBy(() -> service.render(messageFor("exp_01")))
            .isInstanceOf(ExportInputInvalidException.class)
            .extracting(ex -> ((ExportInputInvalidException) ex).errorCode())
            .isEqualTo(ErrorCode.EXPORT_CONTENT_STALE);

        ExportJob persisted = exportRepo.findById("tenant_01", "exp_01").orElseThrow();
        assertThat(persisted.status()).isEqualTo(ExportStatus.FAILED);
        assertThat(persisted.errorCode()).isEqualTo(ErrorCode.EXPORT_CONTENT_STALE);
    }

    @Test
    void runtimeExceptionLeavesRunningStateForRetry() {
        exportRepo.put(jobAt(ExportStatus.QUEUED));
        snapshotPort.set(emptySnapshot());
        storage.failNext(new ExportRuntimeException(
            ErrorCode.EXPORT_RENDER_FAILED, "simulated upload outage"
        ));

        assertThatThrownBy(() -> service.render(messageFor("exp_01")))
            .isInstanceOf(ExportRuntimeException.class);

        ExportJob persisted = exportRepo.findById("tenant_01", "exp_01").orElseThrow();
        // Stays RUNNING — the consumer will requeue and the next attempt
        // will pick up where we left off. Final failure is the consumer's
        // responsibility via failTerminally() after retry exhaustion.
        assertThat(persisted.status()).isEqualTo(ExportStatus.RUNNING);
        assertThat(persisted.errorCode()).isNull();
    }

    @Test
    void terminalJobIsSkipped() {
        ExportJob alreadyDone = ExportJob.builder()
            .id("exp_01").tenantId("tenant_01").meetingId("mtg_01")
            .exportType(ExportType.MEETING).format(ExportFormat.MARKDOWN)
            .dataBoundaryMode(ExportDataBoundaryMode.FULL)
            .inputTranscriptVersion(1).inputMinutesVersion(null)
            .createdBy("user_01").createdAt(NOW)
            .renderOptions(ExportRenderOptions.defaults())
            .status(ExportStatus.SUCCEEDED).fileId("mf_existing").fileHash("sha")
            .downloadExpiresAt(NOW.plusHours(1))
            .build();
        exportRepo.put(alreadyDone);

        RenderOutcome outcome = service.render(messageFor("exp_01"));

        assertThat(outcome.finalStatus()).isEqualTo(ExportStatus.SUCCEEDED);
        assertThat(outcome.fileId()).isNull();   // no new file created
        assertThat(storage.uploaded).isEmpty();  // no upload performed
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void failTerminallyTransitionsRunningToFailed() {
        ExportJob running = jobAt(ExportStatus.QUEUED);
        running.markRunning(NOW);
        exportRepo.put(running);

        service.failTerminally(messageFor("exp_01"), ErrorCode.EXPORT_RENDER_FAILED, "retries exhausted");

        ExportJob persisted = exportRepo.findById("tenant_01", "exp_01").orElseThrow();
        assertThat(persisted.status()).isEqualTo(ExportStatus.FAILED);
        assertThat(persisted.errorCode()).isEqualTo(ErrorCode.EXPORT_RENDER_FAILED);
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    void messageRejectsBlankTenantId() {
        assertThatThrownBy(() -> new ExportJobMessage("", "exp_01", "mtg_01", "trace_x"))
            .isInstanceOf(com.meeting.api.app.common.ApplicationException.class);
    }

    private static ExportJob jobAt(ExportStatus status) {
        return ExportJob.builder()
            .id("exp_01")
            .tenantId("tenant_01")
            .meetingId("mtg_01")
            .exportType(ExportType.MEETING)
            .format(ExportFormat.MARKDOWN)
            .dataBoundaryMode(ExportDataBoundaryMode.FULL)
            .inputTranscriptVersion(1)
            .inputMinutesVersion(null)
            .createdBy("user_01")
            .createdAt(NOW.minusMinutes(1))
            .renderOptions(ExportRenderOptions.defaults())
            .status(status)
            .build();
    }

    private static ExportJobMessage messageFor(String exportId) {
        return new ExportJobMessage("tenant_01", exportId, "mtg_01", "trace_test_01");
    }

    private static MeetingSnapshot emptySnapshot() {
        return new MeetingSnapshot(
            "mtg_01", "Sample meeting",
            "zh",
            null, 1, null,
            List.of(), null, List.of(), List.of(), List.of(), List.of()
        );
    }

    private static final class InMemoryExports implements ExportJobRepository {
        private final Map<String, ExportJob> rows = new HashMap<>();

        @Override public void save(ExportJob job) { rows.put(job.id(), job); }
        @Override public void update(ExportJob job) { rows.put(job.id(), job); }
        @Override public Optional<ExportJob> findById(String tenantId, String exportId) {
            return Optional.ofNullable(rows.get(exportId));
        }
        @Override public PageResult<ExportJob> listByMeeting(String tenantId, String meetingId, String cursor, int limit) {
            return new PageResult<>(List.of(), new PageResult.PageInfo(null, false, limit));
        }
        @Override public List<ExportJob> claimByStatus(String tenantId, ExportStatus status, int limit) {
            return rows.values().stream().filter(j -> j.status() == status).toList();
        }
        void put(ExportJob job) { rows.put(job.id(), job); }
    }

    private static final class InMemoryMeetingFiles implements MeetingFileRepository {
        private final Map<String, MeetingFile> rows = new HashMap<>();
        @Override public MeetingFile save(MeetingFile file) {
            rows.put(file.tenantId() + "|" + file.fileId(), file);
            return file;
        }
        @Override public Optional<MeetingFile> findById(String tenantId, String fileId) {
            return Optional.ofNullable(rows.get(tenantId + "|" + fileId));
        }
        Optional<MeetingFile> byId(String tenantId, String fileId) {
            return findById(tenantId, fileId);
        }
    }

    private static final class StubSnapshotPort implements MeetingSnapshotPort {
        private MeetingSnapshot snapshot;
        void set(MeetingSnapshot s) { this.snapshot = s; }
        @Override public Optional<MeetingSnapshot> loadSnapshot(
            String tenantId, String meetingId, int transcriptVersion, Integer minutesVersion
        ) {
            return Optional.ofNullable(snapshot);
        }
    }

    private static final class StubStorage implements ObjectStorageGateway {
        final List<StorageObject> uploaded = new ArrayList<>();
        private RuntimeException nextFailure;

        void failNext(RuntimeException ex) { this.nextFailure = ex; }

        @Override public String defaultBucket() { return "meeting-local"; }
        @Override public PresignedUrl presignPut(String bucket, String objectKey, int partNumber, String contentType, OffsetDateTime expiresAt) {
            return new PresignedUrl("http://stub/" + bucket + "/" + objectKey, expiresAt, Map.of());
        }
        @Override public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
            return new PresignedUrl("http://stub/" + bucket + "/" + objectKey, expiresAt, Map.of());
        }
        @Override public StorageObject statObject(String bucket, String objectKey) {
            return new StorageObject(bucket, objectKey, 0L, null, null, OffsetDateTime.now());
        }
        @Override public void deleteObject(String bucket, String objectKey) {}
        @Override public StorageObject putObject(String bucket, String objectKey, byte[] bytes, String contentType, String sha256) {
            if (nextFailure != null) {
                RuntimeException ex = nextFailure;
                nextFailure = null;
                throw ex;
            }
            StorageObject obj = new StorageObject(bucket, objectKey, bytes.length, sha256, "etag", OffsetDateTime.now());
            uploaded.add(obj);
            return obj;
        }
    }

    private static final class StubGateway implements ExportGateway {
        private final ExportFormat format;
        private final byte[] bytes;
        StubGateway(ExportFormat format, byte[] bytes) {
            this.format = format;
            this.bytes = bytes;
        }
        @Override public ExportFormat supportedFormat() { return format; }
        @Override public RenderedFile render(ExportJob job, MeetingSnapshot snapshot) {
            return new RenderedFile(bytes, "sha256:abcd1234", bytes.length);
        }
    }

    private static final class RecordingPublisher implements MessagePublisher {
        final List<DomainEvent> events = new ArrayList<>();
        @Override public void publish(DomainEvent event) { events.add(event); }
    }
}
