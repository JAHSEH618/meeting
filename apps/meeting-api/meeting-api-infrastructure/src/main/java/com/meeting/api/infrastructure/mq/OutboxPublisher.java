package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.enums.ProcessingStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventStore outboxEventStore;
    private final RabbitMqPublisher rabbitMqPublisher;
    private final ObjectMapper objectMapper;
    private final MeetingApiMetrics metrics;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisher(
        OutboxEventStore outboxEventStore,
        RabbitMqPublisher rabbitMqPublisher,
        ObjectMapper objectMapper,
        MeetingApiMetrics metrics,
        @Value("${meeting.outbox.batch-size:100}") int batchSize,
        @Value("${meeting.outbox.max-retries:5}") int maxRetries
    ) {
        this.outboxEventStore = outboxEventStore;
        this.rabbitMqPublisher = rabbitMqPublisher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Transactional
    public int publishPending() {
        int published = 0;
        for (OutboxEventRecord record : outboxEventStore.lockPendingBatch(batchSize)) {
            try {
                preflightValidate(record);
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
                metrics.outboxPublishedCounter(record.eventType()).increment();
                published++;
            } catch (ExportJobMessageValidator.InvalidPayloadException ex) {
                // Schema violation — message would be rejected downstream;
                // mark FAILED with a precise error so on-call sees the
                // missing-field message rather than a generic publish error.
                log.warn(
                    "outbox_schema_violation event={} type={} reason={}",
                    record.id(), record.eventType(), ex.getMessage()
                );
                outboxEventStore.markFailed(record.id(), "OUTBOX_PUBLISH_FAILED",
                    "schema violation: " + ex.getMessage(), maxRetries);
                metrics.outboxFailedCounter(record.eventType(), "OUTBOX_PUBLISH_FAILED").increment();
            } catch (Exception ex) {
                outboxEventStore.markFailed(record.id(), "OUTBOX_PUBLISH_FAILED", ex.getMessage(), maxRetries);
                metrics.outboxFailedCounter(record.eventType(), "OUTBOX_PUBLISH_FAILED").increment();
            }
        }
        return published;
    }

    /**
     * Per-event-type schema gate (final-check.md C3). For event types we
     * have a schema for, validate the payload's shape before handing it
     * to the broker so a downstream consumer never has to reject a
     * malformed message.
     */
    private void preflightValidate(OutboxEventRecord record) {
        if ("ExportJobCreatedEvent".equals(record.eventType())) {
            ExportJobMessageValidator.INSTANCE.validate(record.payloadJson(), objectMapper);
        }
    }

    /**
     * Resolve the RabbitMQ routing key for an outbox record.
     *
     * <p>For {@code ProcessingTaskCreatedEvent} the routing is derived from the
     * payload's {@code pipelineSteps[0]} via {@link TaskRouting#routingKeyFor},
     * so a TEXT_EMBEDDING task ({@code pipelineSteps=["RAG_INDEXING"]}) lands on
     * {@code task.embed}, an audio task lands on {@code task.audio-cpu}, etc.
     * Worker-phase + minutes/extraction events keep their legacy {@code task.llm}
     * routing. {@code ExportJobCreatedEvent} goes to the {@code task.export}
     * binding which the {@code export-queue} consumer drains. Visible for tests.
     */
    public String routingKey(OutboxEventRecord record) {
        String eventType = record.eventType();
        if ("ProcessingTaskCreatedEvent".equals(eventType)) {
            List<ProcessingStep> steps = extractPipelineSteps(record.payloadJson());
            if (steps != null && !steps.isEmpty()) {
                try {
                    return TaskRouting.routingKeyFor(steps);
                } catch (IllegalArgumentException ex) {
                    log.warn("outbox_routing_invalid_steps event={} steps={} falling_back",
                        record.id(), steps);
                }
            }
            return "task.audio-cpu";
        }
        if ("ExportJobCreatedEvent".equals(eventType)) {
            return "task.export";
        }
        if (eventType.startsWith("WorkerPhase")) {
            return "task.llm";
        }
        return "task.llm";
    }

    private List<ProcessingStep> extractPipelineSteps(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode arr = root.path("pipelineSteps");
            if (!arr.isArray() || arr.isEmpty()) return null;
            List<ProcessingStep> steps = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                steps.add(ProcessingStep.valueOf(n.asText()));
            }
            return steps;
        } catch (Exception ex) {
            log.warn("outbox_payload_parse_failed reason={}", ex.getMessage());
            return null;
        }
    }
}
