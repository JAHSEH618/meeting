package com.meeting.api.domain.rag;

/**
 * Thrown when ai-worker returns 400 (request schema mismatch), 401 (HMAC
 * failure), or returns a 200 with a malformed envelope.
 *
 * <p>Callers MUST NOT silently degrade — these signal a code-level
 * mismatch that needs operator attention. The RAG query layer should
 * surface this as {@code 500} to the user and bump the
 * {@code rerank/embed.contract_error} alert counter.
 */
public final class AiWorkerContractException extends AiWorkerGatewayException {
    public AiWorkerContractException(String errorCode, String message) {
        super(errorCode, message);
    }

    public AiWorkerContractException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
