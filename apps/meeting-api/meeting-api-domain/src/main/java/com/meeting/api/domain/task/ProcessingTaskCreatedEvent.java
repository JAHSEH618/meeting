package com.meeting.api.domain.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ProcessingTaskCreatedEvent(
    String eventId,
    String tenantId,
    String taskId,
    String meetingId,
    String taskType,
    int attemptNo,
    List<ProcessingStep> pipelineSteps,
    long sequenceNo,
    OffsetDateTime occurredAt
) implements DomainEvent {
    @Override
    public String eventType() {
        return "ProcessingTaskCreatedEvent";
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
            "meetingId", meetingId == null ? "" : meetingId,
            "taskType", taskType,
            "attemptNo", attemptNo,
            "pipelineSteps", pipelineSteps.stream().map(Enum::name).toList()
        );
    }
}
