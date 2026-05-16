package com.meeting.api.app.rag;

import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to {@link KnowledgeChunkReindexRequestedEvent} emitted by
 * {@link ChunkingApplicationService} after the re-chunk transaction commits.
 * Each event is fanned out into one or more TEXT_EMBEDDING processing tasks —
 * one per batch of at most {@link #MAX_CHUNKS_PER_TASK} chunk IDs — so a single
 * worker callback ships a bounded number of embeddings (matching the
 * {@code /internal/embed} 64-text limit but staying under it to leave room
 * for retries and stragglers).
 *
 * <p>The listener fires {@code AFTER_COMMIT} so the chunks are guaranteed
 * persisted before any task message is enqueued. {@code @Transactional} with
 * {@code REQUIRES_NEW} starts a fresh transaction (the source transaction is
 * already committed); failures here do not roll back the chunk persistence,
 * they only abandon the embed task — operators can re-run reindex.
 */
@Component
public class EmbeddingTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingTaskDispatcher.class);

    /**
     * Default chunk count per TEXT_EMBEDDING task. Chosen to stay well under
     * the {@code /internal/embed} request cap (64 strings) while still amortising
     * the bge-m3 GPU warm-up over multiple sentences.
     */
    public static final int MAX_CHUNKS_PER_TASK = 32;

    public static final String TASK_TYPE_TEXT_EMBEDDING = "TEXT_EMBEDDING";
    public static final String DEFAULT_EMBEDDING_MODEL_VERSION = "bge-m3-v1";

    private static final List<ProcessingStep> WORKER_STEPS = List.of(ProcessingStep.RAG_INDEXING);

    private final ProcessingTaskRepository taskRepository;
    private final MessagePublisher messagePublisher;
    private final MeetingApiMetrics metrics;
    private final Clock clock;
    private final int maxChunksPerTask;

    public EmbeddingTaskDispatcher(
        ProcessingTaskRepository taskRepository,
        MessagePublisher messagePublisher,
        MeetingApiMetrics metrics,
        Clock clock
    ) {
        this(taskRepository, messagePublisher, metrics, clock, MAX_CHUNKS_PER_TASK);
    }

    public EmbeddingTaskDispatcher(
        ProcessingTaskRepository taskRepository,
        MessagePublisher messagePublisher,
        MeetingApiMetrics metrics,
        Clock clock,
        int maxChunksPerTask
    ) {
        if (maxChunksPerTask < 1 || maxChunksPerTask > 64) {
            throw new IllegalArgumentException(
                "maxChunksPerTask=" + maxChunksPerTask + " must be in [1, 64]");
        }
        this.taskRepository = taskRepository;
        this.messagePublisher = messagePublisher;
        this.metrics = metrics;
        this.clock = clock;
        this.maxChunksPerTask = maxChunksPerTask;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispatchResult onReindexRequested(KnowledgeChunkReindexRequestedEvent event) {
        if (!event.hasWork()) {
            log.debug("embed_dispatcher_skip_empty tenant={} meetingId={} documentId={}",
                event.tenantId(), event.meetingId(), event.documentId());
            return DispatchResult.empty();
        }
        try {
            DispatchResult result = dispatch(event);
            log.info(
                "embed_dispatcher_dispatched tenant={} meetingId={} documentId={} tasks={} chunks={}",
                event.tenantId(), event.meetingId(), event.documentId(),
                result.taskIds().size(), event.chunkIds().size()
            );
            return result;
        } catch (RuntimeException ex) {
            log.warn(
                "embed_dispatcher_failed tenant={} meetingId={} documentId={} chunks={} reason={}",
                event.tenantId(), event.meetingId(), event.documentId(),
                event.chunkIds().size(), ex.getMessage(), ex
            );
            if (metrics != null) {
                metrics.outboxFailedCounter("EmbeddingDispatcher", "DISPATCH_FAILED").increment();
            }
            throw ex;
        }
    }

    public DispatchResult dispatch(KnowledgeChunkReindexRequestedEvent event) {
        List<KnowledgeChunkReindexRequestedEvent.ChunkRef> chunks = event.chunks();
        List<String> taskIds = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(clock);

        for (int from = 0; from < chunks.size(); from += maxChunksPerTask) {
            int to = Math.min(from + maxChunksPerTask, chunks.size());
            List<KnowledgeChunkReindexRequestedEvent.ChunkRef> batch = chunks.subList(from, to);

            ProcessingTask task = ProcessingTask.create(
                newTaskId(),
                event.tenantId(),
                event.meetingId(),
                TASK_TYPE_TEXT_EMBEDDING,
                WORKER_STEPS,
                now
            );
            task.enqueue(now);
            task.claimLease(
                "worker_dev_001",
                "worker_dev_001:" + task.taskId() + ":" + task.attemptNo(),
                now.plusMinutes(5),
                now
            );
            ProcessingTask saved = taskRepository.save(task);
            taskIds.add(saved.taskId());

            messagePublisher.publish(new ProcessingTaskCreatedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                saved.tenantId(),
                saved.taskId(),
                saved.meetingId(),
                saved.taskType(),
                saved.attemptNo(),
                WORKER_STEPS,
                0L,
                now,
                taskMessagePayload(event, saved, batch)
            ));
        }
        return new DispatchResult(taskIds);
    }

    private static String newTaskId() {
        return "task_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Map<String, Object> taskMessagePayload(
        KnowledgeChunkReindexRequestedEvent event,
        ProcessingTask task,
        List<KnowledgeChunkReindexRequestedEvent.ChunkRef> chunks
    ) {
        Map<String, Object> expectedVersion = new LinkedHashMap<>();
        expectedVersion.put("chunkStrategyVersion", event.chunkStrategyVersion());
        expectedVersion.put("embeddingModelVersion", DEFAULT_EMBEDDING_MODEL_VERSION);
        if (event.transcriptVersion() != null) expectedVersion.put("transcriptVersion", event.transcriptVersion());
        if (event.minutesVersion() != null) expectedVersion.put("minutesVersion", event.minutesVersion());

        List<Map<String, Object>> chunkPayloads = new ArrayList<>(chunks.size());
        List<String> chunkIds = new ArrayList<>(chunks.size());
        for (var c : chunks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", c.id());
            entry.put("content", c.content());
            chunkPayloads.add(entry);
            chunkIds.add(c.id());
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("enableRagIndexing", true);
        options.put("chunkIds", chunkIds);
        options.put("chunks", chunkPayloads);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.taskId());
        payload.put("taskType", task.taskType());
        payload.put("tenantId", task.tenantId());
        if (event.meetingId() != null) payload.put("meetingId", event.meetingId());
        if (event.documentId() != null) payload.put("documentId", event.documentId());
        payload.put("securityLevel", securityLabel(event.securityLevel()));
        payload.put("attemptNo", task.attemptNo());
        payload.put("pipelineSteps", WORKER_STEPS.stream().map(Enum::name).toList());
        payload.put("expectedInputVersion", expectedVersion);
        payload.put("options", options);
        payload.put("traceId", event.traceId() == null || event.traceId().isBlank()
            ? "trace_" + task.taskId()
            : event.traceId());
        return payload;
    }

    private static String securityLabel(SecurityLevel level) {
        return level == null ? "INTERNAL" : level.name();
    }

    /** Result of one fan-out: the new task IDs ai-worker will consume from {@code embed-queue}. */
    public record DispatchResult(List<String> taskIds) {
        public DispatchResult {
            taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
        }
        public static DispatchResult empty() {
            return new DispatchResult(List.of());
        }
    }
}
