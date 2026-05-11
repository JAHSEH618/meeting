package com.meeting.api.client.common;

import java.util.Map;

public record ErrorInfo(
    ErrorCode code,
    String message,
    boolean retryable,
    Map<String, Object> details
) {
    public static ErrorInfo of(ErrorCode code, String message, boolean retryable) {
        return new ErrorInfo(code, message, retryable, Map.of());
    }
}
