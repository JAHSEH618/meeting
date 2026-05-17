package com.meeting.api.domain.compliance;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.DeletionJobStatus;
import java.util.List;
import java.util.Optional;

/** Repository port for {@link DeletionJob}. */
public interface DeletionJobRepository {

    void save(DeletionJob job);

    void update(DeletionJob job);

    Optional<DeletionJob> findById(String tenantId, String jobId);

    PageResult<DeletionJob> listByTenant(String tenantId, String cursor, int limit);

    /**
     * Claim up to {@code limit} jobs in the given status, using
     * {@code SELECT ... FOR UPDATE SKIP LOCKED}. Returned in
     * created_at ASC order so the oldest queued job runs first.
     */
    List<DeletionJob> claimByStatus(String tenantId, DeletionJobStatus status, int limit);
}
