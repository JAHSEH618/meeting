package com.meeting.api.domain.task;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ProcessingTaskRepository {
    ProcessingTask save(ProcessingTask task);

    Optional<ProcessingTask> findById(String tenantId, String taskId);

    Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId);

    Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId);

    List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit);

    record ExpiredLease(String tenantId, String taskId) {}
}
