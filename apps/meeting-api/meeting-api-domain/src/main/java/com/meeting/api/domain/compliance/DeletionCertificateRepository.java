package com.meeting.api.domain.compliance;

import com.meeting.api.client.enums.DeletionScopeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence port for {@code deletion_certificates}. Each finished
 * {@link DeletionJob} writes exactly one certificate row carrying the
 * canonical-JSON SHA-256 hash plus the rolled-up executor outcome
 * for forensic re-validation.
 */
public interface DeletionCertificateRepository {

    void save(DeletionCertificateRecord record);

    Optional<DeletionCertificateRecord> findByJobId(String tenantId, String deletionJobId);

    /**
     * Materialised view of a deletion_certificates row. The
     * {@code objectHashes} list mirrors the deletedFiles entries with
     * sha256 attached when available; phase-1 keeps it empty.
     */
    record DeletionCertificateRecord(
        String id,
        String tenantId,
        String deletionJobId,
        DeletionScopeType scopeType,
        String scopeId,
        List<Map<String, Object>> objectHashes,
        Map<String, Object> deletedRows,
        List<Map<String, Object>> deletedFiles,
        List<String> failedItems,
        String certificateHash,
        OffsetDateTime createdAt
    ) {

        public DeletionCertificateRecord {
            objectHashes = objectHashes == null ? List.of() : List.copyOf(objectHashes);
            deletedRows = deletedRows == null ? Map.of() : Map.copyOf(deletedRows);
            deletedFiles = deletedFiles == null ? List.of() : List.copyOf(deletedFiles);
            failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
        }
    }
}
