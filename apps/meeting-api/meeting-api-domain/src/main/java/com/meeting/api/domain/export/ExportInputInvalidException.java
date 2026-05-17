package com.meeting.api.domain.export;

import com.meeting.api.client.common.ErrorCode;

/**
 * Non-retryable failure caused by malformed input or unsupported shape
 * (unknown format, missing snapshot field, etc.). The export-queue
 * consumer NACKs without requeue and marks the job FAILED.
 */
public class ExportInputInvalidException extends RuntimeException {

    private final ErrorCode errorCode;

    public ExportInputInvalidException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ExportInputInvalidException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
