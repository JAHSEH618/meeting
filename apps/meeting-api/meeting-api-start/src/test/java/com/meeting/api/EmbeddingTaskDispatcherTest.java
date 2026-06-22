package com.meeting.api;

import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.app.rag.EmbeddingTaskDispatcher;
import com.meeting.api.app.rag.EmbeddingTaskDispatcher.DispatchResult;
import com.meeting.api.app.rag.KnowledgeChunkReindexRequestedEvent;
import com.meeting.api.app.rag.KnowledgeChunkReindexRequestedEvent.ChunkRef;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingTaskDispatcherTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-16T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @Test
    void emptyChunkListShortCircuitsWithNoTaskAndNoMessage() {
        var fx = new Fixtures();
        var event = new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", "mtg_01", null, List.<ChunkRef>of(),
            "default-zh-v1", 1, null, null
        );

        DispatchResult result = fx.dispatcher().onReindexRequested(event);

        assertThat(result.taskIds()).isEmpty();
        assertThat(fx.tasks.saved).isEmpty();
        assertThat(fx.publisher.events).isEmpty();
    }

    @Test
    void singleBatchMeetingProducesOneTaskWithExpectedPayload() {
        var fx = new Fixtures();
        var event = new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", "mtg_01", null,
            List.of(ref("c1", "text 1"), ref("c2", "text 2"), ref("c3", "text 3")),
            "default-zh-v1", 3, 1, "trace_abc"
        );

        DispatchResult result = fx.dispatcher().onReindexRequested(event);

        assertThat(result.taskIds()).hasSize(1);
        assertThat(fx.tasks.saved).hasSize(1);

        ProcessingTask task = fx.tasks.saved.get(0);
        assertThat(task.taskType()).isEqualTo("TEXT_EMBEDDING");
        assertThat(task.tenantId()).isEqualTo("tenant_01");
        assertThat(task.meetingId()).isEqualTo("mtg_01");
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.QUEUED);
        assertThat(task.attemptNo()).isEqualTo(1);
        assertThat(task.leaseOwner()).isNull();
        assertThat(task.leaseExpiresAt()).isNull();
        assertThat(task.taskId()).startsWith("task_");

        assertThat(fx.publisher.events).hasSize(1);
        var emitted = (ProcessingTaskCreatedEvent) fx.publisher.events.get(0);
        assertThat(emitted.taskId()).isEqualTo(task.taskId());
        assertThat(emitted.taskType()).isEqualTo("TEXT_EMBEDDING");
        assertThat(emitted.meetingId()).isEqualTo("mtg_01");
        assertThat(emitted.pipelineSteps()).containsExactly(com.meeting.api.client.enums.ProcessingStep.RAG_INDEXING);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) emitted.payload();
        assertThat(payload).containsEntry("taskType", "TEXT_EMBEDDING");
        assertThat(payload).containsEntry("tenantId", "tenant_01");
        assertThat(payload).containsEntry("meetingId", "mtg_01");
        assertThat(payload).doesNotContainKey("documentId");
        assertThat(payload).containsEntry("attemptNo", 1);
        assertThat(payload).containsEntry("pipelineSteps", List.of("RAG_INDEXING"));
        assertThat(payload).containsEntry("traceId", "trace_abc");

        @SuppressWarnings("unchecked")
        Map<String, Object> expectedVersion = (Map<String, Object>) payload.get("expectedInputVersion");
        assertThat(expectedVersion).containsEntry("chunkStrategyVersion", "default-zh-v1");
        assertThat(expectedVersion).containsEntry("embeddingModelVersion", "bge-m3-v1");
        assertThat(expectedVersion).containsEntry("transcriptVersion", 3);
        assertThat(expectedVersion).containsEntry("minutesVersion", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) payload.get("options");
        assertThat(options).containsEntry("enableRagIndexing", true);
        assertThat(options).containsEntry("chunkIds", List.of("c1", "c2", "c3"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) options.get("chunks");
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).containsEntry("id", "c1").containsEntry("content", "text 1");
        assertThat(chunks.get(2)).containsEntry("id", "c3").containsEntry("content", "text 3");
    }

    @Test
    void documentEventEmitsDocumentIdInsteadOfMeetingId() {
        var fx = new Fixtures();
        var event = new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", null, "doc_01",
            List.of(ref("c1", "alpha"), ref("c2", "beta")),
            "default-zh-v1", null, null, null
        );

        DispatchResult result = fx.dispatcher().onReindexRequested(event);

        assertThat(result.taskIds()).hasSize(1);
        var emitted = (ProcessingTaskCreatedEvent) fx.publisher.events.get(0);
        assertThat(emitted.meetingId()).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) emitted.payload();
        assertThat(payload).doesNotContainKey("meetingId");
        assertThat(payload).containsEntry("documentId", "doc_01");
        // traceId defaults from taskId when not provided
        assertThat(payload.get("traceId").toString()).startsWith("trace_task_");
    }

    @Test
    void largeChunkListFansOutIntoMultipleTasksAtMaxBatchSize() {
        var fx = new Fixtures();
        List<ChunkRef> chunkRefs = IntStream.range(0, 100)
            .mapToObj(i -> ref("chunk_" + i, "content_" + i))
            .toList();
        var event = new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", "mtg_large", null, chunkRefs,
            "default-zh-v1", 5, null, null
        );

        DispatchResult result = fx.dispatcher().onReindexRequested(event);

        // 100 chunks / 32 per task = 4 tasks (32 + 32 + 32 + 4)
        assertThat(result.taskIds()).hasSize(4);
        assertThat(fx.tasks.saved).hasSize(4);
        assertThat(fx.publisher.events).hasSize(4);

        List<List<String>> dispatchedBatches = fx.publisher.events.stream()
            .map(e -> (ProcessingTaskCreatedEvent) e)
            .map(e -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) e.payload();
                @SuppressWarnings("unchecked")
                Map<String, Object> opts = (Map<String, Object>) p.get("options");
                @SuppressWarnings("unchecked")
                List<String> cids = (List<String>) opts.get("chunkIds");
                return cids;
            })
            .toList();

        assertThat(dispatchedBatches.get(0)).hasSize(32);
        assertThat(dispatchedBatches.get(1)).hasSize(32);
        assertThat(dispatchedBatches.get(2)).hasSize(32);
        assertThat(dispatchedBatches.get(3)).hasSize(4);

        // Union must equal the input set (no chunk dropped, no chunk duplicated)
        List<String> rejoined = new ArrayList<>();
        dispatchedBatches.forEach(rejoined::addAll);
        assertThat(rejoined).containsExactlyElementsOf(chunkRefs.stream().map(ChunkRef::id).toList());
    }

    @Test
    void customMaxBatchSizeIsHonoured() {
        var fx = new Fixtures();
        EmbeddingTaskDispatcher tiny = new EmbeddingTaskDispatcher(
            fx.tasks, fx.publisher, fx.metrics, CLOCK, 2
        );

        var event = new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", "mtg_x", null,
            List.of(ref("a", "1"), ref("b", "2"), ref("c", "3"), ref("d", "4"), ref("e", "5")),
            "default-zh-v1", 1, null, null
        );

        DispatchResult result = tiny.dispatch(event);

        // 5 chunks / 2 per task = 3 tasks (2 + 2 + 1)
        assertThat(result.taskIds()).hasSize(3);
    }

    @Test
    void invalidBatchSizeIsRejected() {
        var fx = new Fixtures();
        assertThatThrownBy(() -> new EmbeddingTaskDispatcher(fx.tasks, fx.publisher, fx.metrics, CLOCK, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingTaskDispatcher(fx.tasks, fx.publisher, fx.metrics, CLOCK, 65))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reindexEventRejectsBothMeetingAndDocumentIds() {
        assertThatThrownBy(() -> new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", "mtg_a", "doc_b", List.of(ref("c1", "x")),
            "default-zh-v1", null, null, null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", null, null, List.of(ref("c1", "x")),
            "default-zh-v1", null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reindexEventCopiesChunksDefensively() {
        List<ChunkRef> mutable = new ArrayList<>(List.of(ref("c1", "x")));
        var evt = new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", "mtg_01", null, mutable,
            "default-zh-v1", null, null, null
        );
        mutable.add(ref("c2", "y"));
        assertThat(evt.chunks()).hasSize(1);
        assertThat(evt.chunkIds()).containsExactly("c1");
    }

    private static ChunkRef ref(String id, String content) {
        return new ChunkRef(id, content);
    }

    // ── Fixtures ──────────────────────────────────────────────────

    private static final class Fixtures {
        final InMemoryProcessingTaskRepo tasks = new InMemoryProcessingTaskRepo();
        final CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        final MeetingApiMetrics metrics = new MeetingApiMetrics(new SimpleMeterRegistry());

        EmbeddingTaskDispatcher dispatcher() {
            return new EmbeddingTaskDispatcher(tasks, publisher, metrics, CLOCK);
        }
    }

    private static final class InMemoryProcessingTaskRepo implements ProcessingTaskRepository {
        final List<ProcessingTask> saved = new ArrayList<>();
        final Map<String, ProcessingTask> store = new LinkedHashMap<>();

        @Override
        public ProcessingTask save(ProcessingTask task) {
            saved.add(task);
            store.put(task.taskId(), task);
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return Optional.ofNullable(store.get(taskId)).filter(t -> tenantId.equals(t.tenantId()));
        }

        @Override
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return findById(tenantId, taskId);
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return saved.stream()
                .filter(t -> tenantId.equals(t.tenantId()) && meetingId.equals(t.meetingId()))
                .reduce((a, b) -> b);
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }

    private static final class CapturingMessagePublisher implements MessagePublisher {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }
}
