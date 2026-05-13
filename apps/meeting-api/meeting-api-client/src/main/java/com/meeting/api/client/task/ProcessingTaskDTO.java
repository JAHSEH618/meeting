package com.meeting.api.client.task;

import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record ProcessingTaskDTO(
    String taskId,
    String tenantId,
    String meetingId,
    String taskType,
    ProcessingTaskStatus status,
    ProcessingTaskPhase phase,
    int attemptNo,
    String currentStep,
    String lastErrorCode,
    boolean retryable,
    Integer estimatedWaitSeconds,
    OffsetDateTime leaseExpiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<ProcessingTaskStepDTO> steps
) {
}
