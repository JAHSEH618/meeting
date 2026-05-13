package com.meeting.api.infrastructure.mq;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {
    private final OutboxEventStore outboxEventStore;
    private final RabbitMqPublisher rabbitMqPublisher;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisher(
        OutboxEventStore outboxEventStore,
        RabbitMqPublisher rabbitMqPublisher,
        @Value("${meeting.outbox.batch-size:100}") int batchSize,
        @Value("${meeting.outbox.max-retries:5}") int maxRetries
    ) {
        this.outboxEventStore = outboxEventStore;
        this.rabbitMqPublisher = rabbitMqPublisher;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Transactional
    public int publishPending() {
        int published = 0;
        for (OutboxEventRecord record : outboxEventStore.lockPendingBatch(batchSize)) {
            try {
                rabbitMqPublisher.publish(
                    routingKey(record),
                    record.payloadJson(),
                    Map.of(
                        "outboxEventId", record.id(),
                        "tenantId", record.tenantId(),
                        "aggregateType", record.aggregateType(),
                        "aggregateId", record.aggregateId(),
                        "sequenceNo", record.sequenceNo(),
                        "eventType", record.eventType()
                    )
                );
                outboxEventStore.markPublished(record.id());
                published++;
            } catch (Exception ex) {
                outboxEventStore.markFailed(record.id(), "OUTBOX_PUBLISH_FAILED", ex.getMessage(), maxRetries);
            }
        }
        return published;
    }

    private String routingKey(OutboxEventRecord record) {
        if ("ProcessingTaskCreatedEvent".equals(record.eventType())) {
            return "task.audio-cpu";
        }
        if (record.eventType().startsWith("WorkerPhase")) {
            return "task.llm";
        }
        return "task.llm";
    }
}
