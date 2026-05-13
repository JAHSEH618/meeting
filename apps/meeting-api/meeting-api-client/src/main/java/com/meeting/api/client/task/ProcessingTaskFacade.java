package com.meeting.api.client.task;

import java.util.Optional;

public interface ProcessingTaskFacade {
    ProcessingTaskDTO create(CreateProcessingTaskCommand command);

    Optional<ProcessingTaskDTO> get(String tenantId, String taskId);

    ProcessingTaskDTO retry(RetryTaskCommand command);

    ProcessingTaskDTO cancel(CancelTaskCommand command);
}
