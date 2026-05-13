package com.meeting.api.domain.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.Map;

public record ProcessingTaskStepChangedEvent(
    String eventId,
    String tenantId,
    String taskId,
    ProcessingStep stepName,
    StepStatus fromStatus,
    StepStatus toStatus,
    int attemptNo,
    int progress,
    long sequenceNo,
    OffsetDateTime occurredAt
) implements DomainEvent {
    @Override
    public String eventType() {
        return "ProcessingTaskStepChangedEvent";
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
            "stepName", stepName.name(),
            "fromStatus", fromStatus.name(),
            "toStatus", toStatus.name(),
            "attemptNo", attemptNo,
            "progress", progress
        );
    }
}
