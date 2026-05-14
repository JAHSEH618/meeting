package com.meeting.api.client.storage;

public record CreateAudioUploadSessionCommand(
    String tenantId,
    String meetingId,
    String fileName,
    String contentType,
    long fileSizeBytes,
    String fileSha256,
    Integer partSizeBytes,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
