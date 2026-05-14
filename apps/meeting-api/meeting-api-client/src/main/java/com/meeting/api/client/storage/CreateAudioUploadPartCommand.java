package com.meeting.api.client.storage;

public record CreateAudioUploadPartCommand(
    String tenantId,
    String meetingId,
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
