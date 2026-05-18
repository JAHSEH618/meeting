package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.export.ExportApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.export.CreateExportCommand;
import com.meeting.api.client.export.ExportJobDTO;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportJobCompletedEvent;
import com.meeting.api.domain.export.ExportJobCreatedEvent;
import com.meeting.api.domain.export.ExportJobRepository;
import com.meeting.api.domain.export.ExportDownloadRevokedEvent;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.Instant;
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

class ExportApplicationServiceTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.parse("2026-05-18T02:00:00Z");

    private InMemoryExportJobRepository exportRepo;
    private InMemoryMeetingRepository meetingRepo;
    private RecordingMessagePublisher publisher;
    private ToggleableLegalHoldCheck legalHold;
    private ExportApplicationService service;

    @BeforeEach
    void setUp() {
        exportRepo = new InMemoryExportJobRepository();
        meetingRepo = new InMemoryMeetingRepository();
        publisher = new RecordingMessagePublisher();
        legalHold = new ToggleableLegalHoldCheck();
        service = new ExportApplicationService(
            TenantScopedTransaction.immediate(),
            exportRepo,
            meetingRepo,
            legalHold,
            publisher,
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            /* downloadTtlSeconds */ 24 * 3600L
        );
    }

    @Test
    void createPersistsQueuedJobAndPublishesEvent() {
        meetingRepo.put(meetingWithVersions("mtg_01", 3, 2));

        ExportJobDTO dto = service.create(cmdFor("mtg_01", 3, 2, ExportFormat.PDF));

        assertThat(dto.status()).isEqualTo(ExportStatus.QUEUED);
        assertThat(dto.format()).isEqualTo(ExportFormat.PDF);
        assertThat(dto.inputTranscriptVersion()).isEqualTo(3);
        assertThat(dto.inputMinutesVersion()).isEqualTo(2);
        assertThat(dto.stale()).isFalse();
        assertThat(dto.downloadUrl()).isNull();
        assertThat(dto.createdAt()).isEqualTo(FIXED_NOW);

        ExportJob persisted = exportRepo.byId(dto.exportId());
        assertThat(persisted.status()).isEqualTo(ExportStatus.QUEUED);
        assertThat(persisted.renderOptions()).isEqualTo(ExportRenderOptions.defaults());

        assertThat(publisher.events).hasSize(1);
        DomainEvent event = publisher.events.get(0);
        assertThat(event).isInstanceOf(ExportJobCreatedEvent.class);
        assertThat(event.eventType()).isEqualTo("ExportJobCreatedEvent");
        assertThat(event.aggregateId()).isEqualTo(dto.exportId());

        // export-job-message.schema.json requires `traceId` and `createdAt`
        // alongside the meeting/format/expectedInputVersion body; the
        // outbox publisher hands payload() to RabbitMQ verbatim, so the
        // schema-required keys must already be in the event payload.
        Map<String, Object> payload = event.payload();
        assertThat(payload).containsKeys("tenantId", "exportId", "meetingId",
            "format", "expectedInputVersion", "traceId", "createdAt");
        assertThat(payload.get("traceId")).isEqualTo("req_test_01");
        assertThat(payload.get("createdAt")).isNotNull();
    }

    @Test
    void createRejectsWhenLegalHoldActive() {
        meetingRepo.put(meetingWithVersions("mtg_01", 3, 2));
        legalHold.protectedKeys.add("MEETING:mtg_01");

        assertThatThrownBy(() -> service.create(cmdFor("mtg_01", 3, 2, ExportFormat.PDF)))
            .isInstanceOf(ApplicationException.class)
            .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.LEGAL_HOLD_BLOCKED)
            .matches(ex -> ((ApplicationException) ex).httpStatus() == 423);

        assertThat(exportRepo.allRows()).isEmpty();
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void createRejectsWhenTranscriptVersionStale() {
        meetingRepo.put(meetingWithVersions("mtg_01", /* transcriptV */ 5, 2));

        assertThatThrownBy(() -> service.create(cmdFor("mtg_01", 3, 2, ExportFormat.PDF)))
            .isInstanceOf(ApplicationException.class)
            .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.EXPORT_CONTENT_STALE)
            .matches(ex -> ((ApplicationException) ex).httpStatus() == 422);
    }

    @Test
    void createRejectsWhenMinutesVersionStale() {
        meetingRepo.put(meetingWithVersions("mtg_01", 3, /* minutesV */ 5));

        assertThatThrownBy(() -> service.create(cmdFor("mtg_01", 3, 2, ExportFormat.PDF)))
            .isInstanceOf(ApplicationException.class)
            .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.EXPORT_CONTENT_STALE);
    }

    @Test
    void createRejectsWhenMeetingNotFound() {
        assertThatThrownBy(() -> service.create(cmdFor("mtg_unknown", 0, null, ExportFormat.MARKDOWN)))
            .isInstanceOf(ApplicationException.class)
            .matches(ex -> ((ApplicationException) ex).httpStatus() == 404);
    }

    @Test
    void getMarksDtoStaleWhenMeetingVersionAdvances() {
        meetingRepo.put(meetingWithVersions("mtg_01", 3, 2));
        ExportJobDTO created = service.create(cmdFor("mtg_01", 3, 2, ExportFormat.DOCX));

        meetingRepo.put(meetingWithVersions("mtg_01", 4, 2));    // transcript bumped post-export

        ExportJobDTO refetched = service.get("tenant_01", created.exportId()).orElseThrow();
        assertThat(refetched.stale()).isTrue();
    }

    @Test
    void cancelTransitionsQueuedJobAndPublishesCompleted() {
        meetingRepo.put(meetingWithVersions("mtg_01", 3, 2));
        ExportJobDTO created = service.create(cmdFor("mtg_01", 3, 2, ExportFormat.PDF));
        publisher.events.clear();

        service.cancel("tenant_01", created.exportId(), "user_01");

        ExportJob persisted = exportRepo.byId(created.exportId());
        assertThat(persisted.status()).isEqualTo(ExportStatus.CANCELLED);
        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0)).isInstanceOf(ExportJobCompletedEvent.class);
    }

    @Test
    void cancelTerminalJobReturns409() {
        meetingRepo.put(meetingWithVersions("mtg_01", 3, 2));
        ExportJobDTO created = service.create(cmdFor("mtg_01", 3, 2, ExportFormat.PDF));
        ExportJob job = exportRepo.byId(created.exportId());
        job.markRunning(FIXED_NOW.plusSeconds(1));
        job.markSucceeded("file_01", "sha256:x", FIXED_NOW.plusDays(1), FIXED_NOW.plusSeconds(2));
        exportRepo.put(job);

        assertThatThrownBy(() -> service.cancel("tenant_01", created.exportId(), "user_01"))
            .isInstanceOf(ApplicationException.class)
            .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.EXPORT_ALREADY_FINISHED)
            .matches(ex -> ((ApplicationException) ex).httpStatus() == 409);
    }

    @Test
    void revokeLinkOnSucceededFlipsStateAndPublishesEvent() {
        meetingRepo.put(meetingWithVersions("mtg_01", 3, 2));
        ExportJobDTO created = service.create(cmdFor("mtg_01", 3, 2, ExportFormat.PDF));
        ExportJob job = exportRepo.byId(created.exportId());
        job.markRunning(FIXED_NOW.plusSeconds(1));
        job.markSucceeded("file_01", "sha256:x", FIXED_NOW.plusDays(1), FIXED_NOW.plusSeconds(2));
        exportRepo.put(job);
        publisher.events.clear();

        service.revokeLink("tenant_01", created.exportId(), "user_01");

        ExportJob persisted = exportRepo.byId(created.exportId());
        assertThat(persisted.status()).isEqualTo(ExportStatus.REVOKED);
        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0)).isInstanceOf(ExportDownloadRevokedEvent.class);
    }

    private CreateExportCommand cmdFor(String meetingId, int transcriptV, Integer minutesV, ExportFormat format) {
        return new CreateExportCommand(
            "tenant_01", meetingId, format, transcriptV, minutesV,
            /* watermarkText */ null, /* renderOptions */ null, "user_01",
            "req_test_01", "trace_test_01"
        );
    }

    private static Meeting meetingWithVersions(String meetingId, int transcriptVersion, int minutesVersion) {
        try {
            // Build a Meeting via reflection-friendly Builder if available; otherwise use Meeting.create + reflection
            // Easier: reflect into the Builder via the public Meeting.create path
            // The simplest reliable way is to instantiate Meeting through the existing factory and then mutate via a tiny test helper.
            return MeetingTestFactory.create(meetingId, "tenant_01", transcriptVersion, minutesVersion);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static class InMemoryExportJobRepository implements ExportJobRepository {
        private final Map<String, ExportJob> rows = new HashMap<>();

        @Override public void save(ExportJob job) { rows.put(job.id(), job); }
        @Override public void update(ExportJob job) { rows.put(job.id(), job); }

        @Override
        public Optional<ExportJob> findById(String tenantId, String exportId) {
            return Optional.ofNullable(rows.get(exportId));
        }

        @Override
        public PageResult<ExportJob> listByMeeting(String tenantId, String meetingId, String cursor, int limit) {
            List<ExportJob> hits = rows.values().stream()
                .filter(j -> meetingId.equals(j.meetingId()))
                .toList();
            return new PageResult<>(hits, new PageResult.PageInfo(null, false, limit));
        }

        @Override
        public List<ExportJob> claimByStatus(String tenantId, ExportStatus status, int limit) {
            return rows.values().stream().filter(j -> j.status() == status).limit(limit).toList();
        }

        ExportJob byId(String id) { return rows.get(id); }
        java.util.Collection<ExportJob> allRows() { return rows.values(); }
        void put(ExportJob job) { rows.put(job.id(), job); }
    }

    private static class InMemoryMeetingRepository implements MeetingRepository {
        private final Map<String, Meeting> byId = new HashMap<>();

        @Override public Meeting save(Meeting m) { byId.put(m.id(), m); return m; }
        @Override public Optional<Meeting> findById(String tenantId, String meetingId) {
            return Optional.ofNullable(byId.get(meetingId));
        }
        @Override public List<Meeting> findByTenantId(String tenantId) {
            return new ArrayList<>(byId.values());
        }

        void put(Meeting m) { byId.put(m.id(), m); }
    }

    private static class RecordingMessagePublisher implements MessagePublisher {
        final List<DomainEvent> events = new ArrayList<>();
        @Override public void publish(DomainEvent event) { events.add(event); }
    }

    private static class ToggleableLegalHoldCheck implements LegalHoldCheckPort {
        final java.util.Set<String> protectedKeys = new java.util.HashSet<>();
        @Override
        public boolean isProtected(String tenantId, String scopeType, String scopeId) {
            return protectedKeys.contains(scopeType + ":" + scopeId);
        }
    }
}
