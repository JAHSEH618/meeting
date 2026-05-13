package com.meeting.api.client.internal.callback;

import com.meeting.api.client.common.ErrorInfo;
import com.meeting.api.client.enums.ProcessingStep;
import java.time.OffsetDateTime;

public record FailTaskCommand(
    CallbackMetadata metadata,
    String tenantId,
    String meetingId,
    String taskId,
    int attemptNo,
    ProcessingStep failedStep,
    ErrorInfo error,
    String artifactManifestId,
    OffsetDateTime failedAt
) {
}
