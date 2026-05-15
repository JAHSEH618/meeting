package com.meeting.api.client.document;

import com.meeting.api.client.enums.SecurityLevel;
import java.time.OffsetDateTime;

public record DocumentDTO(
    String documentId,
    String tenantId,
    String title,
    String fileId,
    String documentType,
    String status,
    SecurityLevel securityLevel,
    String textExtractionStatus,
    String contentHash,
    String sourceUri,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime deletedAt
) {
}
