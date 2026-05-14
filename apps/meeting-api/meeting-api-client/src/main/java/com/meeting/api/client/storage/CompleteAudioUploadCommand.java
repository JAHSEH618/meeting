package com.meeting.api.client.storage;

import java.util.List;

public record CompleteAudioUploadCommand(
    String tenantId,
    String meetingId,
    String uploadId,
    String fileSha256,
    Long durationMs,
    List<PartCommand> parts,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
    public record PartCommand(
        int partNumber,
        String partSha256,
        String etag
    ) {
    }
}
