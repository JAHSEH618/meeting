package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.rag.EmbeddingsCallbackApplicationService;
import com.meeting.api.app.rag.EmbeddingsCallbackApplicationService.EmbeddingsResult;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.EmbeddingsCallbackCommand;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingsCallbackApplicationServiceTest {

    private static final String SECRET = "callback-secret";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-16T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @Test
    void writeEmbeddingsPersistsCallbackEventAndBulkUpdatesChunks() {
        var fx = fixtures();
        var cmd = command("idem_01", 1, List.of(
            item("chunk_a", new float[]{0.1f, 0.2f}),
            item("chunk_b", new float[]{0.3f, 0.4f})
        ));

        EmbeddingsResult result = fx.service().writeEmbeddings(cmd);

        assertThat(result.replayed()).isFalse();
        assertThat(result.batchId()).isEqualTo("batch_01");
        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(2);

        assertThat(fx.callbacks.records).hasSize(1);
        assertThat(fx.chunks.captured).hasSize(2);
        assertThat(fx.chunks.captured.get("chunk_a")).containsExactly(0.1f, 0.2f);
        assertThat(fx.chunks.captured.get("chunk_b")).containsExactly(0.3f, 0.4f);
        assertThat(fx.chunks.lastModelVersion).isEqualTo("bge-m3-v1");
    }

    @Test
    void idempotentReplayWithSameBodyHashIsNoOp() {
        var fx = fixtures();
        var items = List.of(item("chunk_a", new float[]{0.5f, 0.5f}));
        var cmd = command("idem_replay", 1, items);

        EmbeddingsResult first = fx.service().writeEmbeddings(cmd);
        assertThat(first.replayed()).isFalse();
        assertThat(first.updated()).isEqualTo(1);

        // Same idempotency key + body hash — second call must not double-write.
        EmbeddingsResult second = fx.service().writeEmbeddings(cmd);
        assertThat(second.replayed()).isTrue();
        assertThat(second.updated()).isEqualTo(0);
        assertThat(second.requested()).isEqualTo(1);

        // The repository markEmbeddings was only called once (first call).
        assertThat(fx.chunks.callCount).isEqualTo(1);
    }

    @Test
    void idempotencyBodyHashConflictThrows() {
        var fx = fixtures();
        // Insert a prior callback event with the same idempotency key but a different body hash.
        fx.callbacks.records.add(new CallbackEventRepository.CallbackEventRecord(
            "tenant_01", "task_01", "worker_01", "idem_conflict",
            "different_body_hash_sha256",
            1, "worker_01:task_01:1", "", 200, null, "trace_01", NOW
        ));

        var cmd = command("idem_conflict", 1, List.of(item("chunk_a", new float[]{0.1f, 0.2f})));

        assertThatThrownBy(() -> fx.service().writeEmbeddings(cmd))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("body hash conflict");
    }

    @Test
    void taskAttemptMismatchThrows() {
        var fx = fixtures();
        // command says attempt=2 but the task is on attempt=1
        var cmd = command("idem_attempt", 2, List.of(item("chunk_a", new float[]{0.1f, 0.2f})));

        assertThatThrownBy(() -> fx.service().writeEmbeddings(cmd))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("attempt does not match");
    }

    @Test
    void missingTaskThrows() {
        var fx = fixtures();
        fx.tasks.task = null;
        var cmd = command("idem_missing", 1, List.of(item("chunk_a", new float[]{0.1f, 0.2f})));

        assertThatThrownBy(() -> fx.service().writeEmbeddings(cmd))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("task not found");
    }

    @Test
    void emptyItemsRegistersCallbackEventButSkipsRepoCall() {
        var fx = fixtures();
        var cmd = command("idem_empty", 1, List.of());

        EmbeddingsResult result = fx.service().writeEmbeddings(cmd);

        assertThat(result.updated()).isEqualTo(0);
        assertThat(result.requested()).isEqualTo(0);
        assertThat(result.replayed()).isFalse();
        assertThat(fx.callbacks.records).hasSize(1);
        assertThat(fx.chunks.callCount).isEqualTo(0);
    }

    @Test
    void unmatchedChunkIdsAreReflectedInUpdatedCount() {
        var fx = fixtures();
        // Repo simulates that only chunk_a exists; chunk_ghost yields 0 rows.
        fx.chunks.knownIds.add("chunk_a");

        var cmd = command("idem_partial", 1, List.of(
            item("chunk_a", new float[]{0.1f, 0.2f}),
            item("chunk_ghost", new float[]{0.3f, 0.4f})
        ));

        EmbeddingsResult result = fx.service().writeEmbeddings(cmd);

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(1);
    }

    // ── Fixtures ──────────────────────────────────────────────────

    private static Fixtures fixtures() {
        return new Fixtures();
    }

    private static final class Fixtures {
        final InMemoryProcessingTaskRepo tasks = runningTask();
        final InMemoryCallbackEvents callbacks = new InMemoryCallbackEvents();
        final CapturingKnowledgeChunkRepo chunks = new CapturingKnowledgeChunkRepo();

        EmbeddingsCallbackApplicationService service() {
            return new EmbeddingsCallbackApplicationService(
                tasks,
                callbacks,
                chunks,
                TenantScopedTransaction.immediate(),
                new CallbackSecurityVerifier(SECRET, 300, CLOCK),
                CLOCK
            );
        }
    }

    private static InMemoryProcessingTaskRepo runningTask() {
        ProcessingTask task = ProcessingTask.create(
            "task_01",
            "tenant_01",
            null,
            "TEXT_EMBEDDING",
            List.of(ProcessingStep.RAG_INDEXING),
            NOW
        );
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        return new InMemoryProcessingTaskRepo(task);
    }

    private static EmbeddingsCallbackCommand command(String idempotencyKey, int attemptNo, List<EmbeddingsCallbackCommand.Item> items) {
        String body = "{\"batchId\":\"" + idempotencyKey + "\"}";  // arbitrary, just used for hash
        return new EmbeddingsCallbackCommand(
            metadata(idempotencyKey, body),
            "tenant_01",
            "task_01",
            attemptNo,
            "batch_01",
            "DOCUMENT",
            "bge-m3-v1",
            "default-zh-v1",
            items
        );
    }

    private static EmbeddingsCallbackCommand.Item item(String chunkId, float[] values) {
        return new EmbeddingsCallbackCommand.Item(
            chunkId, chunkId, 1, "hash_" + chunkId,
            new EmbeddingsCallbackCommand.Embedding("FLOAT32_ARRAY", values.length, values)
        );
    }

    private static CallbackMetadata metadata(String idempotencyKey, String body) {
        String bodyHash = sha256(body);
        String method = "POST";
        String path = "/internal/processing-tasks/task_01/embeddings";
        String nonce = "nonce_" + idempotencyKey;
        String signingString = NOW + "\n" + nonce + "\n" + method + "\n" + path + "\n" + bodyHash;
        return new CallbackMetadata(
            "worker_01",
            1,
            "worker_01:task_01:1",
            method,
            "req_" + idempotencyKey,
            "trace_" + idempotencyKey,
            NOW,
            nonce,
            idempotencyKey,
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

    // ── In-memory fakes ───────────────────────────────────────────

    private static final class InMemoryProcessingTaskRepo implements ProcessingTaskRepository {
        ProcessingTask task;

        InMemoryProcessingTaskRepo(ProcessingTask task) { this.task = task; }

        @Override public ProcessingTask save(ProcessingTask t) { this.task = t; return t; }
        @Override public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return task != null && task.taskId().equals(taskId) && task.tenantId().equals(tenantId)
                ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return Optional.empty();
        }
        @Override public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }

    private static final class InMemoryCallbackEvents implements CallbackEventRepository {
        final List<CallbackEventRecord> records = new ArrayList<>();

        @Override public Optional<CallbackEventRecord> findByIdempotencyKey(String tenantId, String idempotencyKey) {
            return records.stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.idempotencyKey().equals(idempotencyKey))
                .findFirst();
        }
        @Override public CallbackEventRecord save(CallbackEventRecord record) { records.add(record); return record; }
    }

    private static final class CapturingKnowledgeChunkRepo implements KnowledgeChunkRepository {
        final Map<String, float[]> captured = new LinkedHashMap<>();
        final java.util.Set<String> knownIds = new java.util.HashSet<>();
        String lastModelVersion = null;
        int callCount = 0;

        @Override
        public int markEmbeddings(String tenantId, Map<String, EmbeddingResult> embeddingsByChunkId) {
            callCount++;
            int touched = 0;
            for (var entry : embeddingsByChunkId.entrySet()) {
                captured.put(entry.getKey(), entry.getValue().values());
                lastModelVersion = entry.getValue().modelVersion();
                if (knownIds.isEmpty() || knownIds.contains(entry.getKey())) {
                    touched++;
                }
            }
            return touched;
        }

        @Override
        public int markStaleForMeeting(String tenantId, String meetingId) {
            return 0;
        }

        @Override
        public void saveAll(Collection<com.meeting.api.domain.rag.KnowledgeChunk> chunks) {
        }

        @Override
        public int markStaleForDocument(String tenantId, String documentId) {
            return 0;
        }

        @Override
        public int updateStaleStatus(String tenantId, Collection<String> chunkIds, StaleStatus newStatus) {
            return 0;
        }
    }
}
