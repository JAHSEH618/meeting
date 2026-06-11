package com.meeting.api.client.document;

import java.time.OffsetDateTime;

public record DocumentDTO(
    String documentId,
    String tenantId,
    String title,
    String fileId,
    String documentType,
    String status,
    String textExtractionStatus,
    String contentHash,
    String sourceUri,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime deletedAt
) {
}
