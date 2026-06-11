package com.meeting.api.client.storage;

public record CreateGenericFilePartCommand(
    String tenantId,
    String uploadId,
    int partNumber,
    long sizeBytes,
    String partSha256,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
