package com.meeting.api.client.internal.callback;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.StepStatus;

public record StepCallbackCommand(
    CallbackMetadata metadata,
    String tenantId,
    String meetingId,
    String taskId,
    int attemptNo,
    ProcessingStep stepName,
    StepStatus status,
    Integer progress,
    String errorCode,
    String artifactManifestId
) {
}
