package com.meeting.api.client.storage;

public record AbortGenericFileUploadCommand(
    String tenantId,
    String uploadId,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
