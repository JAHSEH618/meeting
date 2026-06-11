package com.meeting.api.client.document;


public record CreateDocumentCommand(
    String tenantId,
    String title,
    String fileId,
    String documentType,
    String contentHash,
    String createdBy,
    String requestId,
    String traceId,
    String idempotencyKey
) {
}
