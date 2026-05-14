package com.meeting.api.client.storage;

public record AbortAudioUploadCommand(
    String tenantId,
    String meetingId,
    String uploadId,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
