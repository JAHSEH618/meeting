package com.meeting.api.client.internal.callback;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record CompleteWorkerPhaseCommand(
    CallbackMetadata metadata,
    String tenantId,
    String meetingId,
    String taskId,
    int attemptNo,
    String phase,
    ProcessingTaskStatus status,
    List<ProcessingStep> completedSteps,
    List<SkippedStep> skippedSteps,
    String speakerEnrollmentId,
    String artifactManifestId,
    OffsetDateTime finishedAt
) {
    public CompleteWorkerPhaseCommand(
        CallbackMetadata metadata,
        String tenantId,
        String meetingId,
        String taskId,
        int attemptNo,
        String phase,
        ProcessingTaskStatus status,
        List<ProcessingStep> completedSteps,
        List<SkippedStep> skippedSteps,
        String artifactManifestId,
        OffsetDateTime finishedAt
    ) {
        this(metadata, tenantId, meetingId, taskId, attemptNo, phase, status,
            completedSteps, skippedSteps, null, artifactManifestId, finishedAt);
    }

    public record SkippedStep(
        ProcessingStep stepName,
        String reason
    ) {
    }
}
