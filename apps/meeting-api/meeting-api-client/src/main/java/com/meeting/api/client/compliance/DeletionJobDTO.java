package com.meeting.api.client.compliance;

import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.DeletionScopeType;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * DTO returned by the deletion-job endpoints. Mirrors
 * {@code /admin/deletion-jobs/{jobId}} 's OpenAPI schema; the actual
 * deletion executors fill the {@code *Json} maps when they complete.
 */
public record DeletionJobDTO(
    String deletionJobId,
    DeletionScopeType scopeType,
    String scopeId,
    DeletionJobStatus status,
    String requestedBy,
    String approvedBy,
    boolean legalHoldChecked,
    Map<String, Object> deletedRows,
    Map<String, Object> deletedFiles,
    Map<String, Object> kmsKeysDestroyed,
    String certificateHash,
    String errorCode,
    OffsetDateTime createdAt,
    OffsetDateTime finishedAt
) {}
