package com.meeting.api.client.compliance;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.DeletionScopeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Application-layer facade for {@code /admin/deletion-jobs}. */
public interface DeletionJobFacade {

    DeletionJobDTO create(CreateDeletionJobCommand command);

    Optional<DeletionJobDTO> get(String tenantId, String deletionJobId);

    PageResult<DeletionJobDTO> list(String tenantId, String cursor, int limit);

    /**
     * Return the certificate row for a finished deletion job. Empty
     * for jobs that haven't reached a terminal status yet.
     */
    Optional<DeletionCertificateDTO> getCertificate(String tenantId, String deletionJobId);

    record DeletionCertificateDTO(
        String certificateId,
        String deletionJobId,
        DeletionScopeType scopeType,
        String scopeId,
        List<Map<String, Object>> objectHashes,
        Map<String, Object> deletedRows,
        List<Map<String, Object>> deletedFiles,
        List<String> failedItems,
        String certificateHash,
        OffsetDateTime createdAt
    ) {}
}
