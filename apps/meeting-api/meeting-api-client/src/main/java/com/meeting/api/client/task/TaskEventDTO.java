package com.meeting.api.client.task;

import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.TaskEventType;
import java.time.OffsetDateTime;
import java.util.List;

public record TaskEventDTO(
    String eventId,
    long sequenceNo,
    TaskEventType eventType,
    String taskId,
    String meetingId,
    String stepName,
    String status,
    ProcessingTaskPhase phase,
    Integer progress,
    Boolean retryable,
    String errorCode,
    OffsetDateTime emittedAt,
    Integer attemptNo,
    Integer transcriptVersion,
    String artifactManifestId,
    List<String> completedSteps,
    List<ProcessingTaskStepDTO> steps,
    OffsetDateTime leaseExpiresAt
) {
}
