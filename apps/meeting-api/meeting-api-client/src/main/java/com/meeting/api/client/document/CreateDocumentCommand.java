package com.meeting.api.client.document;

import com.meeting.api.client.enums.SecurityLevel;

public record CreateDocumentCommand(
    String tenantId,
    String title,
    String fileId,
    String documentType,
    SecurityLevel securityLevel,
    String contentHash,
    String createdBy,
    String requestId,
    String traceId,
    String idempotencyKey
) {
}
