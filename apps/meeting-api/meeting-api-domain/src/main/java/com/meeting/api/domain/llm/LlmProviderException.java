package com.meeting.api.domain.llm;

import com.meeting.api.client.common.ErrorCode;

/**
 * LLM provider call failed (timeout, rate limit, malformed response).
 * The {@link ErrorCode} discriminator drives retryable-or-not and HTTP mapping in adapter layer.
 */
public final class LlmProviderException extends RuntimeException {
    private final ErrorCode errorCode;

    public LlmProviderException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LlmProviderException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
