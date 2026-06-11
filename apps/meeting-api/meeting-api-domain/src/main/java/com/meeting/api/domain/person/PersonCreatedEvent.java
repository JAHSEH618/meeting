package com.meeting.api.domain.person;

import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record PersonCreatedEvent(
    String eventId,
    String tenantId,
    String personId,
    String displayName,
    String email,
    String externalId,
    String createdBy,
    long sequenceNo,
    OffsetDateTime occurredAt
) implements DomainEvent {
    @Override
    public String eventType() {
        return "PersonCreatedEvent";
    }

    @Override
    public String aggregateType() {
        return "Person";
    }

    @Override
    public String aggregateId() {
        return personId;
    }

    @Override
    public String payloadVersion() {
        return "v1";
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("personId", personId);
        payload.put("displayName", displayName);
        payload.put("email", email == null ? "" : email);
        payload.put("externalId", externalId == null ? "" : externalId);
        payload.put("createdBy", createdBy == null ? "" : createdBy);
        return payload;
    }
}
