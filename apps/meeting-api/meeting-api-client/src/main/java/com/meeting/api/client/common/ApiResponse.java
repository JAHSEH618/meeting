package com.meeting.api.client.common;

public record ApiResponse<T>(
    boolean success,
    T data,
    ErrorInfo error,
    String requestId,
    String traceId
) {
    public static <T> ApiResponse<T> ok(T data, String requestId, String traceId) {
        return new ApiResponse<>(true, data, null, requestId, traceId);
    }

    public static <T> ApiResponse<T> failed(ErrorInfo error, String requestId, String traceId) {
        return new ApiResponse<>(false, null, error, requestId, traceId);
    }
}
