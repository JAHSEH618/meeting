package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.enums.ProcessingStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /**
     * Event types that MUST be published to the RabbitMQ {@code task.*}
     * exchange because a remote consumer is wired to drain them.
     *
     * <ul>
     *   <li>{@code ProcessingTaskCreatedEvent} — drained by ai-worker.</li>
     *   <li>{@code ExportJobCreatedEvent} — drained by the Java
     *       {@code ExportQueueConsumer}.</li>
     * </ul>
     */
    private static final Set<String> ROUTABLE_EVENT_TYPES = Set.of(
        "ProcessingTaskCreatedEvent",
        "ExportJobCreatedEvent"
    );

    /**
     * Domain events the application layer publishes via
     * {@link com.meeting.api.domain.task.MessagePublisher} so the
     * audit trail is complete, but which Spring also fans out through
     * {@code ApplicationEventPublisher} for in-process listeners
     * ({@code WorkerPhaseCompletedListener},
     * {@code MinutesGeneratedRagIndexer}, etc.). No RabbitMQ consumer
     * is interested in these — sending them to {@code task.llm}
     * pollutes the worker queue, so we mark them {@code SKIPPED}
     * after the outbox publisher picks them up.
     */
    private static final Set<String> SKIPPED_EVENT_TYPES = Set.of(
        "WorkerPhaseCompletedEvent",
        "ProcessingTaskStepChangedEvent",
        "MeetingCreatedEvent",
        "MeetingDocumentAttachedEvent",
        "MeetingDocumentDetachedEvent",
        "MeetingGlossaryUpdatedEvent",
        "MinutesGeneratedEvent",
        "ExportJobCompletedEvent",
        "ExportDownloadRevokedEvent"
    );

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
    public int publishPending(String tenantId) {
        int published = 0;
        for (OutboxEventRecord record : outboxEventStore.lockPendingBatch(tenantId, batchSize)) {
            String eventType = record.eventType();
            if (SKIPPED_EVENT_TYPES.contains(eventType)) {
                outboxEventStore.markSkipped(record.id(),
                    "in-process domain event — no RabbitMQ consumer");
                continue;
            }
            if (!ROUTABLE_EVENT_TYPES.contains(eventType)) {
                log.warn(
                    "outbox_unroutable event={} type={} — marked DLQ (allow-list miss)",
                    record.id(), eventType
                );
                outboxEventStore.markUnroutable(record.id(),
                    "unknown event type: " + eventType);
                metrics.outboxFailedCounter(eventType, "OUTBOX_UNROUTABLE_EVENT_TYPE").increment();
                continue;
            }
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
                metrics.outboxPublishedCounter(eventType).increment();
                published++;
            } catch (ExportJobMessageValidator.InvalidPayloadException
                    | ProcessingTaskMessageValidator.InvalidPayloadException ex) {
                // Schema violation — message would be rejected downstream;
                // mark FAILED with a precise error so on-call sees the
                // missing-field message rather than a generic publish error.
                log.warn(
                    "outbox_schema_violation event={} type={} reason={}",
                    record.id(), eventType, ex.getMessage()
                );
                outboxEventStore.markFailed(record.id(), "OUTBOX_PUBLISH_FAILED",
                    "schema violation: " + ex.getMessage(), maxRetries);
                metrics.outboxFailedCounter(eventType, "OUTBOX_PUBLISH_FAILED").increment();
            } catch (Exception ex) {
                outboxEventStore.markFailed(record.id(), "OUTBOX_PUBLISH_FAILED", ex.getMessage(), maxRetries);
                metrics.outboxFailedCounter(eventType, "OUTBOX_PUBLISH_FAILED").increment();
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
        } else if ("ProcessingTaskCreatedEvent".equals(record.eventType())) {
            ProcessingTaskMessageValidator.INSTANCE.validate(record.payloadJson(), objectMapper);
        }
    }

    /**
     * Resolve the RabbitMQ routing key for an outbox record. Only
     * called for event types in {@link #ROUTABLE_EVENT_TYPES}; anything
     * else has already been skipped or marked DLQ in
     * {@link #publishPending(String)}, so this method never has to
     * choose a "default" routing for unknown types.
     *
     * <p>For {@code ProcessingTaskCreatedEvent} the routing is derived
     * from the payload's {@code pipelineSteps[0]} via
     * {@link TaskRouting#routingKeyFor}, so a TEXT_EMBEDDING task
     * ({@code pipelineSteps=["RAG_INDEXING"]}) lands on
     * {@code task.embed}, an audio task lands on
     * {@code task.audio-cpu}, etc. Missing/invalid steps mark the
     * event FAILED rather than silently falling back. Visible for tests.
     */
    public String routingKey(OutboxEventRecord record) {
        String eventType = record.eventType();
        if ("ProcessingTaskCreatedEvent".equals(eventType)) {
            List<ProcessingStep> steps = extractPipelineSteps(record.payloadJson());
            if (steps == null || steps.isEmpty()) {
                throw new IllegalArgumentException(
                    "ProcessingTaskCreatedEvent payload missing pipelineSteps");
            }
            return TaskRouting.routingKeyFor(steps);
        }
        if ("ExportJobCreatedEvent".equals(eventType)) {
            return "task.export";
        }
        // Should never reach here — the allow-list in publishPending()
        // filters out anything that's not routable before we get a
        // routing key. Throw rather than silently routing to task.llm.
        throw new IllegalArgumentException(
            "no routing key for event type: " + eventType);
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
