package com.meeting.api.client.storage;

import java.util.List;

public record CompleteGenericFileUploadCommand(
    String tenantId,
    String uploadId,
    String fileSha256,
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
