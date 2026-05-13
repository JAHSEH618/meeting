package com.meeting.api.domain.meeting;

import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.Map;

public record MeetingCreatedEvent(
    String eventId,
    String tenantId,
    String meetingId,
    String title,
    SecurityLevel securityLevel,
    String createdBy,
    long sequenceNo,
    OffsetDateTime occurredAt
) implements DomainEvent {
    @Override
    public String eventType() {
        return "MeetingCreatedEvent";
    }

    @Override
    public String aggregateType() {
        return "Meeting";
    }

    @Override
    public String aggregateId() {
        return meetingId;
    }

    @Override
    public String payloadVersion() {
        return "v1";
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of(
            "meetingId", meetingId,
            "title", title,
            "securityLevel", securityLevel.name(),
            "createdBy", createdBy == null ? "" : createdBy
        );
    }
}
