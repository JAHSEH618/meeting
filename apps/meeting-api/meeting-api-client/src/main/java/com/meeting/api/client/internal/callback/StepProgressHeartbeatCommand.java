package com.meeting.api.client.internal.callback;

import com.meeting.api.client.enums.ProcessingStep;
import java.time.OffsetDateTime;

public record StepProgressHeartbeatCommand(
    CallbackMetadata metadata,
    String tenantId,
    String meetingId,
    String taskId,
    int attemptNo,
    ProcessingStep stepName,
    int progress,
    OffsetDateTime heartbeatAt
) {
}
