package com.meeting.api.domain.rag;

import java.util.List;

/**
 * Synchronous gateway to ai-worker's {@code POST /internal/embed}.
 *
 * <p>Used by the RAG query layer to embed a user question (or any short
 * text) into a 1024-dimensional dense vector for pgvector retrieval.
 * Bulk corpus embedding goes through the async TEXT_EMBEDDING task path,
 * not this gateway.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Sign every request with the shared {@code meeting.security.ai-worker.hmac-secret}.</li>
 *   <li>Time-bound the call at {@code embed-timeout-ms} (default 3000ms).</li>
 *   <li>Translate transport failures into {@link AiWorkerUnavailableException}
 *       and contract-level failures into {@link AiWorkerContractException}.</li>
 * </ul>
 */
public interface EmbeddingGateway {
    EmbedResult embed(EmbedRequest request);

    record EmbedRequest(
        String tenantId,
        List<String> texts,
        String requestId,
        String traceId
    ) {
    }

    record EmbedResult(
        String modelVersion,
        int dimension,
        List<float[]> vectors
    ) {
    }
}
