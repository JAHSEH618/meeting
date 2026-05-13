package com.meeting.api.domain.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record WorkerPhaseCompletedEvent(
    String eventId,
    String tenantId,
    String taskId,
    String taskType,
    int attemptNo,
    ProcessingTaskStatus workerStatus,
    List<ProcessingStep> completedSteps,
    List<SkippedStep> skippedSteps,
    String artifactManifestId,
    long sequenceNo,
    OffsetDateTime occurredAt
) implements DomainEvent {
    @Override
    public String eventType() {
        return "WorkerPhaseCompletedEvent";
    }

    @Override
    public String aggregateType() {
        return "ProcessingTask";
    }

    @Override
    public String aggregateId() {
        return taskId;
    }

    @Override
    public String payloadVersion() {
        return "v1";
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of(
            "taskId", taskId,
            "taskType", taskType,
            "attemptNo", attemptNo,
            "workerStatus", workerStatus.name(),
            "completedSteps", completedSteps.stream().map(Enum::name).toList(),
            "skippedSteps", skippedSteps.stream()
                .map(step -> Map.of("stepName", step.stepName().name(), "reason", step.reason()))
                .toList(),
            "artifactManifestId", artifactManifestId == null ? "" : artifactManifestId
        );
    }

    public record SkippedStep(
        ProcessingStep stepName,
        String reason
    ) {
    }
}
