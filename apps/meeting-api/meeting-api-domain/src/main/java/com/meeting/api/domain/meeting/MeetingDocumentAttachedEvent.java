package com.meeting.api.domain.meeting;

import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.Map;

public record MeetingDocumentAttachedEvent(
    String eventId,
    String tenantId,
    String meetingId,
    String linkId,
    String documentId,
    DocumentRole role,
    String attachedBy,
    long sequenceNo,
    OffsetDateTime occurredAt
) implements DomainEvent {
    @Override
    public String eventType() {
        return "MeetingDocumentAttachedEvent";
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
            "documentId", documentId,
            "role", role.name(),
            "attachedBy", attachedBy == null ? "" : attachedBy
        );
    }
}
