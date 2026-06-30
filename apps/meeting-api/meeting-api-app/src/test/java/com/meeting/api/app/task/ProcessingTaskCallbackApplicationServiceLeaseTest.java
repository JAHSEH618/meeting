package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.StepCallbackCommand;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.CallbackNonceRepository;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskCallbackApplicationServiceLeaseTest {
    private static final String SECRET = "callback-secret";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-13T02:00:00Z");

    @Test
    void claimLeaseUsesConfiguredLeaseDuration() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(queuedTask());
        ProcessingTaskCallbackApplicationService service = service(tasks, 45L);

        service.updateStep(new StepCallbackCommand(
            metadata("nonce_claim"),
            "tenant_01",
            "meeting_01",
            "task_01",
            1,
            ProcessingStep.AUDIO_PREPROCESS,
            StepStatus.RUNNING,
            0,
            null,
            null
        ));

        assertThat(tasks.task.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(45));
    }

    @Test
    void heartbeatRenewsLeaseUsingConfiguredLeaseDuration() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(runningTask());
        ProcessingTaskCallbackApplicationService service = service(tasks, 45L);

        service.updateStep(new StepCallbackCommand(
            metadata("nonce_heartbeat"),
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

        assertThat(tasks.task.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(45));
    }

    private static ProcessingTaskCallbackApplicationService service(
        InMemoryTaskRepository tasks,
        long leaseDurationSeconds
    ) {
        return new ProcessingTaskCallbackApplicationService(
            tasks,
            new InMemoryCallbackEvents(),
            event -> {},
            TenantScopedTransaction.immediate(),
            new CallbackSecurityVerifier(SECRET, 300L, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), new InMemoryCallbackNonceRepository()),
            new InMemoryTranscriptRepository(),
            event -> {},
            null,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            leaseDurationSeconds
        );
    }

    private static ProcessingTask queuedTask() {
        ProcessingTask task = ProcessingTask.create(
            "task_01",
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            List.of(
                ProcessingStep.AUDIO_UPLOAD,
                ProcessingStep.AUDIO_PREPROCESS,
                ProcessingStep.ASR
            ),
            NOW
        );
        task.enqueue(NOW);
        return task;
    }

    private static ProcessingTask runningTask() {
        ProcessingTask task = queuedTask();
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        return task;
    }

    private static CallbackMetadata metadata(String nonce) {
        String method = "PATCH";
        String path = "/internal/processing-tasks/task_01/steps/AUDIO_PREPROCESS";
        String bodyHash = sha256("{}");
        // Sign over the seconds-precision timestamp the production
        // CallbackSecurityVerifier uses; OffsetDateTime.toString() would drop ':00'.
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC).format(NOW);
        String signingString = ts + "\n" + nonce + "\n" + method + "\n" + path + "\n" + bodyHash;
        return new CallbackMetadata(
            "worker_01",
            1,
            "worker_01:task_01:1",
            method,
            "req_" + nonce,
            "trace_01",
            NOW,
            nonce,
            "idem_" + nonce,
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
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return findById(tenantId, taskId);
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
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

    private static final class InMemoryCallbackNonceRepository implements CallbackNonceRepository {
        private final List<String> nonces = new ArrayList<>();

        @Override
        public boolean exists(String tenantId, String nonce) {
            return nonces.contains(tenantId + ":" + nonce);
        }

        @Override
        public boolean record(String tenantId, String nonce, String workerId, String taskId, String stepName) {
            String key = tenantId + ":" + nonce;
            if (nonces.contains(key)) {
                return false;
            }
            nonces.add(key);
            return true;
        }

        @Override
        public int cleanupExpired(OffsetDateTime before) {
            return 0;
        }
    }

    private static final class InMemoryTranscriptRepository implements TranscriptRepository {
        @Override
        public int currentTranscriptVersion(String tenantId, String meetingId) {
            return 0;
        }

        @Override
        public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion) {
            return List.of();
        }

        @Override
        public Optional<TranscriptSegmentRecord> findSegment(String tenantId, String meetingId, String segmentId, int transcriptVersion) {
            return Optional.empty();
        }

        @Override
        public void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments) {
        }

        @Override
        public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion) {
        }

        @Override
        public void applySegmentEdit(String tenantId, String meetingId, String segmentId, int expectedTranscriptVersion, String editedText, String changedBy, String editReason, OffsetDateTime now) {
        }
    }
}
