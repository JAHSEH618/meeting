package com.meeting.api.domain.common;

import java.time.OffsetDateTime;
import java.util.Map;

public interface DomainEvent {
    String eventId();

    String eventType();

    String aggregateType();

    String aggregateId();

    String tenantId();

    long sequenceNo();

    OffsetDateTime occurredAt();

    String payloadVersion();

    Map<String, Object> payload();
}
