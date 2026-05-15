package com.meeting.api.domain.rag;

/**
 * Thrown when ai-worker returns 503, times out, or the connection drops.
 *
 * <p>Callers MAY degrade gracefully when the operation has a fallback path
 * (e.g. rerank → RRF fusion). Embedding has no equivalent fallback, so the
 * RAG query layer surfaces this as {@code 503 RAG_TEMPORARILY_UNAVAILABLE}
 * to the user rather than silently returning low-quality results.
 */
public final class AiWorkerUnavailableException extends AiWorkerGatewayException {
    public AiWorkerUnavailableException(String errorCode, String message) {
        super(errorCode, message);
    }

    public AiWorkerUnavailableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
