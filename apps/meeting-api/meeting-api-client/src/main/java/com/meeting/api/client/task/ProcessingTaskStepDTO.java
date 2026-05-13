package com.meeting.api.client.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingStepUpdateSource;
import com.meeting.api.client.enums.StepStatus;
import java.time.OffsetDateTime;

public record ProcessingTaskStepDTO(
    ProcessingStep stepName,
    StepStatus status,
    int progress,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    OffsetDateTime heartbeatAt,
    Integer attemptNo,
    String leaseOwner,
    String workerId,
    Boolean retryable,
    String errorCode,
    ProcessingStepUpdateSource source
) {
}
