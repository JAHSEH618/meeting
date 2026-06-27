package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.common.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxOrphanedTaskRepublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-13T02:30:00Z"), ZoneOffset.UTC);

    @Test
    void rePublishesOriginalPayloadWithBumpedAttempt() {
        ObjectMapper objectMapper = new ObjectMapper();
        String original = """
            {"taskId":"task_01","tenantId":"tenant_01","meetingId":"mtg_01",
             "taskType":"MEETING_FULL_PIPELINE","attemptNo":1,
             "pipelineSteps":["AUDIO_PREPROCESS","ASR"],"audioUri":"tos://bucket/a.wav"}
            """;
        CapturingOutboxEventStore store = new CapturingOutboxEventStore(objectMapper, original);
        OutboxOrphanedTaskRepublisher republisher = new OutboxOrphanedTaskRepublisher(store, objectMapper, CLOCK);

        boolean result = republisher.republish("tenant_01", "task_01", 2);

        assertThat(result).isTrue();
        assertThat(store.appended).isNotNull();
        assertThat(store.appended.eventType()).isEqualTo("ProcessingTaskCreatedEvent");
        assertThat(store.appended.aggregateType()).isEqualTo("ProcessingTask");
        assertThat(store.appended.aggregateId()).isEqualTo("task_01");

        Map<String, Object> payload = store.appended.payload();
        // The bumped attempt is what lets the worker's callbacks pass fencing.
        assertThat(payload.get("attemptNo")).isEqualTo(2);
        // Every other field of the original message is preserved verbatim.
        assertThat(payload.get("audioUri")).isEqualTo("tos://bucket/a.wav");
        assertThat(payload.get("taskType")).isEqualTo("MEETING_FULL_PIPELINE");
        assertThat(payload.get("meetingId")).isEqualTo("mtg_01");
    }

    @Test
    void returnsFalseWhenNoOriginalPayloadExists() {
        ObjectMapper objectMapper = new ObjectMapper();
        CapturingOutboxEventStore store = new CapturingOutboxEventStore(objectMapper, null);
        OutboxOrphanedTaskRepublisher republisher = new OutboxOrphanedTaskRepublisher(store, objectMapper, CLOCK);

        boolean result = republisher.republish("tenant_01", "task_01", 2);

        assertThat(result).isFalse();
        assertThat(store.appended).isNull();
    }

    /** Hand-rolled fake so the test needs no DB and no mocking framework. */
    private static final class CapturingOutboxEventStore extends OutboxEventStore {
        private final String payloadToReturn;
        private DomainEvent appended;

        CapturingOutboxEventStore(ObjectMapper objectMapper, String payloadToReturn) {
            super(null, objectMapper);
            this.payloadToReturn = payloadToReturn;
        }

        @Override
        public Optional<String> findLatestPayloadJson(
            String tenantId, String aggregateType, String aggregateId, String eventType
        ) {
            return Optional.ofNullable(payloadToReturn);
        }

        @Override
        public OutboxEventRecord append(DomainEvent event) {
            this.appended = event;
            return null;
        }
    }
}
