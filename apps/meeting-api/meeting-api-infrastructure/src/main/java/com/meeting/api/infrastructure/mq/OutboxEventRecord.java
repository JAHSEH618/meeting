package com.meeting.api.infrastructure.mq;

import java.time.OffsetDateTime;

public record OutboxEventRecord(
    String id,
    String tenantId,
    String aggregateType,
    String aggregateId,
    long sequenceNo,
    String eventType,
    String payloadJson,
    String dedupeKey,
    int retryCount,
    OffsetDateTime createdAt
) {
}
