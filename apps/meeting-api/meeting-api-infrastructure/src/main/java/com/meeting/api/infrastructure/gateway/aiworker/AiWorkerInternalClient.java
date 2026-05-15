package com.meeting.api.infrastructure.gateway.aiworker;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Transport-shaped seam over ai-worker's HMAC-protected internal API.
 *
 * <p>Implementations sign the request with the shared HMAC secret, send
 * it within {@code timeoutMs}, and parse the {@code ApiResponse} envelope
 * defined in {@code ai-worker-internal-api.yaml}. The returned
 * {@link JsonNode} is the unwrapped {@code data} object on success;
 * non-200 envelopes are translated into {@link com.meeting.api.domain.rag.AiWorkerUnavailableException}
 * (503 / 5xx / timeout / I/O) or
 * {@link com.meeting.api.domain.rag.AiWorkerContractException} (400 / 401 /
 * malformed envelope).
 */
public interface AiWorkerInternalClient {
    JsonNode call(
        String method,
        String path,
        JsonNode body,
        String tenantId,
        String requestId,
        String traceId,
        int timeoutMs
    );
}
