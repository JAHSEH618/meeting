package com.meeting.api.domain.task;

import java.util.Optional;

public interface ProcessingTaskRepository {
    ProcessingTask save(ProcessingTask task);

    Optional<ProcessingTask> findById(String tenantId, String taskId);

    Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId);
}
