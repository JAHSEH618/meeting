package com.meeting.api.client.compliance;

import com.meeting.api.client.common.PageResult;
import java.util.Optional;

/** Application-layer facade for {@code /admin/deletion-jobs}. */
public interface DeletionJobFacade {

    DeletionJobDTO create(CreateDeletionJobCommand command);

    Optional<DeletionJobDTO> get(String tenantId, String deletionJobId);

    PageResult<DeletionJobDTO> list(String tenantId, String cursor, int limit);
}
