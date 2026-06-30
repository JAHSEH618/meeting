package com.meeting.api.domain.rag;

import java.util.List;

/**
 * Synchronous gateway to ai-worker's {@code POST /internal/rerank}.
 *
 * <p>Used in-line by the RAG query pipeline after retrieval + permission
 * filtering to reorder up to 50 candidate chunks. Callers MUST pre-filter
 * by tenant, permission, status, and stale_status — ai-worker only runs
 * model inference, not policy.
 *
 * <p>When the gateway throws {@link AiWorkerUnavailableException}, callers
 * SHOULD fall back to RRF order and record a degraded-rerank metric.
 * Because rerank only reorders an already-authorized candidate pool, callers
 * SHOULD also fall back to RRF order on {@link AiWorkerContractException}
 * rather than failing the whole query — rerank is best-effort, not
 * authoritative.
 */
public interface RerankGateway {
    RerankResult rerank(RerankRequest request);

    record RerankRequest(
        String tenantId,
        String query,
        List<RerankCandidate> candidates,
        int topN,
        String modelVersion,
        String requestId,
        String traceId
    ) {
    }

    record RerankCandidate(
        String chunkId,
        String sourceType,
        String text,
        double rrfScore,
        Integer sourceVersion
    ) {
    }

    record RerankResult(
        String modelVersion,
        List<RankedItem> items
    ) {
    }

    record RankedItem(
        String chunkId,
        int rank,
        double rerankScore
    ) {
    }
}
