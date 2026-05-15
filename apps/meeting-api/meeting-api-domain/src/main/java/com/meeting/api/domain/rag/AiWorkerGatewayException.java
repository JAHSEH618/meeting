package com.meeting.api.domain.rag;

/**
 * Base exception for failures interacting with the ai-worker internal API.
 *
 * <p>App-layer code distinguishes between {@link AiWorkerUnavailableException}
 * (degradable: rerank falls back to RRF, callback retries on next iteration)
 * and {@link AiWorkerContractException} (NOT degradable: HMAC mismatch /
 * invalid request / corrupted envelope — surface as a hard error and alert).
 */
public abstract class AiWorkerGatewayException extends RuntimeException {
    private final String errorCode;

    protected AiWorkerGatewayException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected AiWorkerGatewayException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
