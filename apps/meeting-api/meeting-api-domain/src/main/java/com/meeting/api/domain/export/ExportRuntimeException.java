package com.meeting.api.domain.export;

import com.meeting.api.client.common.ErrorCode;

/**
 * Retryable transient failure during rendering (e.g., LibreOffice
 * subprocess timeout, IO error). The export-queue consumer retries
 * up to its configured cap before letting the message hit DLQ.
 */
public class ExportRuntimeException extends RuntimeException {

    private final ErrorCode errorCode;

    public ExportRuntimeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ExportRuntimeException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
