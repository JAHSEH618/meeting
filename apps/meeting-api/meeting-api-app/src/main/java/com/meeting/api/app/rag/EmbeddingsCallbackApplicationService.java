package com.meeting.api.app.rag;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.EmbeddingsCallbackCommand;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository.EmbeddingResult;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Embeddings callback handler — terminus of the
 * {@code POST /internal/processing-tasks/{taskId}/embeddings} flow.
 *
 * <p>Validates HMAC, persists the idempotency record, verifies the attempt
 * number against the current task state, then bulk-writes the embedding
 * vectors via {@link KnowledgeChunkRepository#markEmbeddings}. The chunks
 * themselves were already persisted at {@code ChunkingApplicationService}
 * time with {@code embedding = NULL}; this method only fills in the column.
 *
 * <p>Unlike speaker embeddings, text embeddings are <strong>not</strong>
 * KMS-envelope-encrypted at rest — pgvector needs plaintext vectors for
 * cosine similarity. The transport security stays HMAC + internal TLS, and
 * the embedding values never appear in any public DTO or log.
 */
@Service
public class EmbeddingsCallbackApplicationService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingsCallbackApplicationService.class);

    private final ProcessingTaskRepository taskRepository;
    private final CallbackEventRepository callbackEventRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final CallbackSecurityVerifier securityVerifier;
    private final Clock clock;

    public EmbeddingsCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.callbackEventRepository = callbackEventRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.securityVerifier = securityVerifier;
        this.clock = clock;
    }

    public EmbeddingsResult writeEmbeddings(EmbeddingsCallbackCommand command) {
        securityVerifier.verify(
            command.metadata(),
            command.tenantId(),
            command.metadata().workerId(),
            command.taskId(),
            "RAG_EMBEDDINGS"
        );
        return tenantScopedTransaction.execute(
            command.tenantId(),
            null,
            command.metadata().requestId(),
            () -> writeInTransaction(command)
        );
    }

    private EmbeddingsResult writeInTransaction(EmbeddingsCallbackCommand command) {
        ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));

        // I10: Validate attempt number
        if (task.attemptNo() != command.attemptNo()) {
            throw new IllegalStateException(
                "callback attempt does not match current attempt: callback=" + command.attemptNo()
                    + " current=" + task.attemptNo()
            );
        }

        // I10: Validate lease owner (防止过期租约写入)
        if (task.leaseOwner() == null || !task.leaseOwner().equals(command.metadata().leaseOwner())) {
            throw new IllegalStateException(
                "callback lease owner does not match current lease: callback=" + command.metadata().leaseOwner()
                    + " current=" + task.leaseOwner()
            );
        }

        if (!persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata())) {
            // Idempotent replay — same body hash, ignore quietly.
            log.info("embeddings_callback_replay tenant={} task={} batch={}",
                command.tenantId(), command.taskId(), command.embeddingBatchId());
            return new EmbeddingsResult(command.embeddingBatchId(), 0, command.items().size(), true);
        }
        if (command.items().isEmpty()) {
            log.info("embeddings_callback_empty tenant={} task={}", command.tenantId(), command.taskId());
            return new EmbeddingsResult(command.embeddingBatchId(), 0, 0, false);
        }

        Map<String, EmbeddingResult> byChunkId = new LinkedHashMap<>();
        for (var item : command.items()) {
            float[] values = item.embedding().values();
            byChunkId.put(item.chunkId(), new EmbeddingResult(values, command.embeddingModelVersion()));
        }

        int touched = knowledgeChunkRepository.markEmbeddings(command.tenantId(), byChunkId);
        log.info(
            "embeddings_callback_persisted tenant={} task={} batch={} requested={} updated={} model={}",
            command.tenantId(), command.taskId(), command.embeddingBatchId(),
            command.items().size(), touched, command.embeddingModelVersion()
        );
        return new EmbeddingsResult(command.embeddingBatchId(), touched, command.items().size(), false);
    }

    private boolean persistCallbackEvent(String tenantId, String taskId, CallbackMetadata metadata) {
        var result = callbackEventRepository.recordOnce(new CallbackEventRepository.CallbackEventRecord(
            tenantId, taskId, metadata.workerId(), metadata.idempotencyKey(),
            metadata.bodySha256(), metadata.attemptNo(), metadata.leaseOwner(),
            "", 200, null, metadata.traceId(), OffsetDateTime.now(clock)
        ));
        if (result.status() == CallbackEventRepository.RecordStatus.BODY_HASH_CONFLICT) {
            throw new IllegalStateException("callback idempotency body hash conflict");
        }
        return result.status() == CallbackEventRepository.RecordStatus.RECORDED;
    }

    /**
     * @param batchId  the embeddingBatchId echoed back to the caller
     * @param updated  number of {@code knowledge_chunks} rows actually written
     * @param requested number of items the caller submitted
     * @param replayed true if this was a duplicate of an earlier callback (idempotent no-op)
     */
    public record EmbeddingsResult(String batchId, int updated, int requested, boolean replayed) {
    }
}
