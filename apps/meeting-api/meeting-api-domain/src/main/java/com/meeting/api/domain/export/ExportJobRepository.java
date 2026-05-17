package com.meeting.api.domain.export;

import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.common.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * Repository port for {@link ExportJob}. Implementations live in the
 * infrastructure layer (JDBC + RLS); the domain layer only depends on
 * this contract.
 */
public interface ExportJobRepository {

    /** Insert a new job. Throws if the id already exists. */
    void save(ExportJob job);

    /** Update mutable fields (status, file*, downloadRevokedAt, errorCode, finishedAt, updatedAt). */
    void update(ExportJob job);

    Optional<ExportJob> findById(String tenantId, String exportId);

    /** Paginated list by meeting (cursor uses createdAt + id). */
    PageResult<ExportJob> listByMeeting(
        String tenantId, String meetingId, String cursor, int limit
    );

    /**
     * Claim up to {@code limit} jobs in a target status using
     * {@code SELECT ... FOR UPDATE SKIP LOCKED}. Used by the
     * outbox-driven export-queue consumer for retry recovery.
     */
    List<ExportJob> claimByStatus(String tenantId, ExportStatus status, int limit);
}
