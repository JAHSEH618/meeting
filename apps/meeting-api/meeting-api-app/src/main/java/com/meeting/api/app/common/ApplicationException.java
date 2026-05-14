package com.meeting.api.app.common;

import com.meeting.api.client.common.ErrorCode;

public class ApplicationException extends RuntimeException {
    private final ErrorCode errorCode;
    private final int httpStatus;
    private final boolean retryable;

    public ApplicationException(ErrorCode errorCode, int httpStatus, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
