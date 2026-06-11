package com.meeting.api.domain.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository {
    String save(DocumentRecord record);

    Optional<DocumentRecord> findById(String tenantId, String documentId);

    List<DocumentRecord> listByTenant(String tenantId, boolean includeDeleted);

    void updateExtractionStatus(String tenantId, String documentId, String extractionStatus,
                                 String status, OffsetDateTime now);

    void softDelete(String tenantId, String documentId, OffsetDateTime now);

    record DocumentRecord(
        String id,
        String tenantId,
        String projectId,
        String title,
        String fileId,
        String documentType,
        String status,
        
        String textExtractionStatus,
        String sourceUri,
        String contentHash,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt
    ) {
    }
}
