package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.app.task.ProcessingTaskCallbackApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.ErrorInfo;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.CompleteWorkerPhaseCommand;
import com.meeting.api.client.internal.callback.FailTaskCommand;
import com.meeting.api.client.internal.callback.StepCallbackCommand;
import com.meeting.api.client.internal.callback.TranscriptCallbackCommand;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import com.meeting.api.domain.transcript.TranscriptRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskCallbackApplicationServiceTest {
    private static final String SECRET = "callback-secret";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-13T02:00:00Z");

    @Test
    void heartbeatUpdatesTaskWithoutCallbackEvent() {
        InMemoryTaskRepository tasks = runningTask();
        InMemoryCallbackEvents callbacks = new InMemoryCallbackEvents();
        ProcessingTaskCallbackApplicationService service = service(tasks, callbacks, new CapturingPublisher());

        var dto = service.updateStep(new StepCallbackCommand(
            metadata("PATCH", "/internal/processing-tasks/task_01/steps/AUDIO_PREPROCESS", "{}"),
            "tenant_01",
            "meeting_01",
            "task_01",
            1,
            ProcessingStep.AUDIO_PREPROCESS,
            StepStatus.RUNNING,
            25,
            null,
            null
        ));

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(dto.steps())
            .filteredOn(step -> step.stepName() == ProcessingStep.AUDIO_PREPROCESS)
            .singleElement()
            .satisfies(step -> assertThat(step.progress()).isEqualTo(25));
        assertThat(callbacks.records).isEmpty();
    }

    @Test
    void completeWorkerPhaseMovesPhaseButNotTaskTerminalAndPublishesEvent() {
        InMemoryTaskRepository tasks = runningTask();
        CapturingPublisher publisher = new CapturingPublisher();
        ProcessingTaskCallbackApplicationService service = service(tasks, new InMemoryCallbackEvents(), publisher);

        var dto = service.completeWorkerPhase(new CompleteWorkerPhaseCommand(
            metadata("POST", "/internal/processing-tasks/task_01/complete", "{}"),
            "tenant_01",
            "meeting_01",
            "task_01",
            1,
            "WORKER_DAG",
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE),
            List.of(),
            null,
            NOW.plusMinutes(1)
        ));

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.WORKER_DAG_DONE);
        assertThat(publisher.events).singleElement().isInstanceOf(WorkerPhaseCompletedEvent.class);
    }

    @Test
    void failMovesTaskToTerminalFailed() {
        InMemoryTaskRepository tasks = runningTask();
        ProcessingTaskCallbackApplicationService service = service(tasks, new InMemoryCallbackEvents(), new CapturingPublisher());

        var dto = service.fail(new FailTaskCommand(
            metadata("POST", "/internal/processing-tasks/task_01/fail", "{}"),
            "tenant_01",
            "meeting_01",
            "task_01",
            1,
            ProcessingStep.ASR,
            ErrorInfo.of(ErrorCode.ASR_RUNTIME_ERROR, "failed", true),
            null,
            NOW.plusMinutes(1)
        ));

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.FAILED);
        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(dto.lastErrorCode()).isEqualTo("ASR_RUNTIME_ERROR");
    }

    @Test
    void transcriptCallbackPersistsSegmentsAndBumpsVersion() {
        InMemoryTaskRepository tasks = runningTask();
        InMemoryTranscriptRepository transcripts = new InMemoryTranscriptRepository();
        ProcessingTaskCallbackApplicationService service = service(tasks, new InMemoryCallbackEvents(), new CapturingPublisher(), transcripts);

        var dto = service.writeTranscript(transcriptCommand(metadata("POST", "/internal/processing-tasks/task_01/transcript", "{}")));

        assertThat(dto.currentStep()).isEqualTo("TRANSCRIPT_MERGE");
        assertThat(transcripts.version).isEqualTo(1);
        assertThat(transcripts.segments).singleElement().satisfies(segment -> {
            assertThat(segment.originalText()).isEqualTo("hello world");
            assertThat(segment.currentText()).isEqualTo("hello world");
            assertThat(segment.segmentIndex()).isEqualTo(0);
        });
    }

    @Test
    void transcriptCallbackReplayWithSameBodyHashIsIdempotent() {
        InMemoryTaskRepository tasks = runningTask();
        InMemoryCallbackEvents callbacks = new InMemoryCallbackEvents();
        InMemoryTranscriptRepository transcripts = new InMemoryTranscriptRepository();
        ProcessingTaskCallbackApplicationService service = service(tasks, callbacks, new CapturingPublisher(), transcripts);
        CallbackMetadata metadata = metadata("POST", "/internal/processing-tasks/task_01/transcript", "{}");

        service.writeTranscript(transcriptCommand(metadata));
        service.writeTranscript(transcriptCommand(metadata));

        assertThat(transcripts.version).isEqualTo(1);
        assertThat(transcripts.replaceCount).isEqualTo(1);
        assertThat(callbacks.records).hasSize(1);
    }

    private static ProcessingTaskCallbackApplicationService service(
        InMemoryTaskRepository tasks,
        InMemoryCallbackEvents callbacks,
        CapturingPublisher publisher
    ) {
        return service(tasks, callbacks, publisher, new InMemoryTranscriptRepository());
    }

    private static ProcessingTaskCallbackApplicationService service(
        InMemoryTaskRepository tasks,
        InMemoryCallbackEvents callbacks,
        CapturingPublisher publisher,
        InMemoryTranscriptRepository transcripts
    ) {
        return new ProcessingTaskCallbackApplicationService(
            tasks,
            callbacks,
            publisher,
            TenantScopedTransaction.immediate(),
            new CallbackSecurityVerifier(SECRET, 300, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)),
            transcripts,
            event -> {},
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static InMemoryTaskRepository runningTask() {
        ProcessingTask task = ProcessingTask.create(
            "task_01",
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_UPLOAD, ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE),
            NOW
        );
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        return new InMemoryTaskRepository(task);
    }

    private static CallbackMetadata metadata(String method, String path, String body) {
        String bodyHash = sha256(body);
        OffsetDateTime timestamp = NOW;
        String nonce = "nonce_01";
        String signingString = timestamp + "\n" + nonce + "\n" + method + "\n" + path + "\n" + bodyHash;
        return new CallbackMetadata(
            "worker_01",
            1,
            "worker_01:task_01:1",
            method,
            "req_01",
            "trace_01",
            timestamp,
            nonce,
            "idem_01",
            "hmac-sha256=" + hmac(signingString),
            path,
            bodyHash
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TranscriptCallbackCommand transcriptCommand(CallbackMetadata metadata) {
        return new TranscriptCallbackCommand(
            metadata,
            "tenant_01",
            "meeting_01",
            "task_01",
            1,
            1,
            List.of(new TranscriptCallbackCommand.Segment(
                "seg_01",
                0,
                1200,
                "SPEAKER_00",
                "hello world",
                BigDecimal.valueOf(0.98),
                BigDecimal.valueOf(0.97),
                BigDecimal.ZERO,
                "SEGMENT"
            )),
            Map.of(),
            null
        );
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    private static final class InMemoryTaskRepository implements ProcessingTaskRepository {
        private ProcessingTask task;

        private InMemoryTaskRepository(ProcessingTask task) {
            this.task = task;
        }

        @Override
        public ProcessingTask save(ProcessingTask task) {
            this.task = task;
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return tenantId.equals(task.tenantId()) && taskId.equals(task.taskId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public java.util.List<ExpiredLease> findExpiredLeases(java.time.OffsetDateTime now, int limit) {
            return java.util.List.of();
        }
    }

    private static final class InMemoryCallbackEvents implements CallbackEventRepository {
        private final List<CallbackEventRecord> records = new ArrayList<>();

        @Override
        public Optional<CallbackEventRecord> findByIdempotencyKey(String tenantId, String idempotencyKey) {
            return records.stream()
                .filter(record -> record.tenantId().equals(tenantId))
                .filter(record -> record.idempotencyKey().equals(idempotencyKey))
                .findFirst();
        }

        @Override
        public CallbackEventRecord save(CallbackEventRecord record) {
            records.add(record);
            return record;
        }
    }

    private static final class InMemoryTranscriptRepository implements TranscriptRepository {
        private int version;
        private int replaceCount;
        private List<TranscriptSegmentRecord> segments = List.of();

        @Override
        public int currentTranscriptVersion(String tenantId, String meetingId) {
            return version;
        }

        @Override
        public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion) {
            return transcriptVersion == version ? segments : List.of();
        }

        @Override
        public void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments) {
            this.segments = List.copyOf(segments);
            replaceCount++;
        }

        @Override
        public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion) {
            this.version = transcriptVersion;
        }
    }
}
