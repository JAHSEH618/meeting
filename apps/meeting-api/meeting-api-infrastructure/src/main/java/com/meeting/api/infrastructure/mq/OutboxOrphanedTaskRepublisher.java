package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.domain.task.OrphanedTaskRepublisher;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recovers the original {@code ProcessingTaskCreatedEvent} payload from the
 * outbox and re-appends it (carrying the bumped attempt number) so an orphaned
 * task is actually re-dispatched to RabbitMQ. Runs in the caller's transaction,
 * so the re-append commits atomically with the task's requeue.
 */
@Component
public class OutboxOrphanedTaskRepublisher implements OrphanedTaskRepublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxOrphanedTaskRepublisher.class);
    private static final String AGGREGATE_TYPE = "ProcessingTask";
    private static final String EVENT_TYPE = "ProcessingTaskCreatedEvent";

    private final OutboxEventStore outboxEventStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxOrphanedTaskRepublisher(
        OutboxEventStore outboxEventStore,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.outboxEventStore = outboxEventStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean republish(String tenantId, String taskId, int newAttemptNo) {
        Optional<String> original = outboxEventStore.findLatestPayloadJson(
            tenantId, AGGREGATE_TYPE, taskId, EVENT_TYPE
        );
        if (original.isEmpty()) {
            LOG.warn("orphan_republish_no_payload task={} tenant={}", taskId, tenantId);
            return false;
        }

        ObjectNode payload;
        try {
            JsonNode node = objectMapper.readTree(original.get());
            if (!node.isObject()) {
                LOG.warn("orphan_republish_non_object_payload task={} tenant={}", taskId, tenantId);
                return false;
            }
            payload = (ObjectNode) node;
        } catch (JsonProcessingException e) {
            LOG.warn("orphan_republish_unparseable_payload task={} tenant={} reason={}",
                taskId, tenantId, e.getMessage());
            return false;
        }

        // The re-dispatched message must carry the bumped attempt number so the
        // worker's callbacks pass the task's attempt/lease fencing.
        payload.put("attemptNo", newAttemptNo);

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = objectMapper.convertValue(payload, Map.class);

        ProcessingTaskCreatedEvent event = new ProcessingTaskCreatedEvent(
            null,
            tenantId,
            taskId,
            text(payload, "meetingId"),
            text(payload, "taskType"),
            newAttemptNo,
            pipelineSteps(payload),
            0L,
            OffsetDateTime.now(clock),
            payloadMap
        );
        outboxEventStore.append(event);
        LOG.info("orphan_republished task={} tenant={} newAttempt={}", taskId, tenantId, newAttemptNo);
        return true;
    }

    private static String text(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<ProcessingStep> pipelineSteps(ObjectNode payload) {
        // The outbox publisher derives the routing key from the payload JSON's
        // pipelineSteps, not from this typed list, so an unknown entry is simply
        // skipped rather than failing the whole re-dispatch.
        List<ProcessingStep> steps = new ArrayList<>();
        JsonNode arr = payload.get("pipelineSteps");
        if (arr != null && arr.isArray()) {
            for (JsonNode entry : arr) {
                try {
                    steps.add(ProcessingStep.valueOf(entry.asText()));
                } catch (IllegalArgumentException ignored) {
                    // skip unknown step name
                }
            }
        }
        return steps;
    }
}
