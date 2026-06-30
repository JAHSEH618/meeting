package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.infrastructure.mq.OutboxEventRecord;
import com.meeting.api.infrastructure.mq.OutboxEventStore;
import com.meeting.api.infrastructure.mq.OutboxPublisher;
import com.meeting.api.infrastructure.mq.RabbitMqPublisher;
import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPublisherRoutingTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-16T09:00:00Z");

    @Test
    void textEmbeddingTaskRoutesToEmbedQueue() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ProcessingTaskCreatedEvent",
            "{\"taskType\":\"TEXT_EMBEDDING\",\"pipelineSteps\":[\"RAG_INDEXING\"]}");

        assertThat(publisher.routingKey(record)).isEqualTo("task.embed");
    }

    @Test
    void audioPreprocessTaskRoutesToAudioCpuQueue() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ProcessingTaskCreatedEvent",
            "{\"taskType\":\"MEETING_FULL_PIPELINE\","
                + "\"pipelineSteps\":[\"AUDIO_PREPROCESS\",\"ASR\",\"DIARIZATION\"]}");

        assertThat(publisher.routingKey(record)).isEqualTo("task.audio-cpu");
    }

    @Test
    void speakerEnrollmentRoutesToGpuSpeakerQueue() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ProcessingTaskCreatedEvent",
            "{\"taskType\":\"SPEAKER_ENROLLMENT\","
                + "\"pipelineSteps\":[\"SPEAKER_EMBEDDING\",\"SPEAKER_MATCHING\"]}");

        assertThat(publisher.routingKey(record)).isEqualTo("task.gpu-speaker");
    }

    @Test
    void asrFirstRoutesToGpuAsr() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ProcessingTaskCreatedEvent",
            "{\"pipelineSteps\":[\"ASR\",\"DIARIZATION\"]}");

        assertThat(publisher.routingKey(record)).isEqualTo("task.gpu-asr");
    }

    @Test
    void missingPipelineStepsThrowsRatherThanFallingBack() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ProcessingTaskCreatedEvent",
            "{\"taskType\":\"MEETING_FULL_PIPELINE\"}");

        // Previously fell back to task.audio-cpu — that masked bad
        // payloads. Now we surface the problem so publishPending marks
        // the row FAILED with a precise reason.
        assertThatThrownBy(() -> publisher.routingKey(record))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing pipelineSteps");
    }

    @Test
    void exportJobCreatedRoutesToExportQueue() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ExportJobCreatedEvent",
            "{\"tenantId\":\"tenant_01\",\"exportId\":\"exp_xxx\","
                + "\"meetingId\":\"mtg_01\",\"format\":\"PDF\","
                + "\"expectedInputVersion\":{\"transcriptVersion\":3}}");

        assertThat(publisher.routingKey(record)).isEqualTo("task.export");
    }

    @Test
    void unknownEventTypeNoLongerHasARoutingKey() {
        // Previously defaulted to task.llm — that pushed garbage onto
        // the worker LLM queue. Strict allow-list now throws so
        // publishPending marks the row DLQ as unroutable.
        OutboxPublisher publisher = newPublisher();
        assertThatThrownBy(() -> publisher.routingKey(record("MysteryEvent", "{}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no routing key");
    }

    @Test
    void publishPendingSkipsInternalDomainEvents() {
        FakeStore store = new FakeStore(List.of(
            record("MeetingCreatedEvent", "evt_a", "{\"tenantId\":\"tenant_01\"}"),
            record("MinutesGeneratedEvent", "evt_b", "{\"tenantId\":\"tenant_01\"}"),
            record("MeetingDocumentAttachedEvent", "evt_c", "{\"tenantId\":\"tenant_01\"}"),
            record("WorkerPhaseCompletedEvent", "evt_d", "{\"tenantId\":\"tenant_01\"}"),
            record("ProcessingTaskStepChangedEvent", "evt_e", "{\"tenantId\":\"tenant_01\"}"),
            record("ExportJobCompletedEvent", "evt_f", "{\"tenantId\":\"tenant_01\"}"),
            record("ExportDownloadRevokedEvent", "evt_g", "{\"tenantId\":\"tenant_01\"}"),
            record("MeetingDocumentDetachedEvent", "evt_h", "{\"tenantId\":\"tenant_01\"}"),
            record("MeetingGlossaryUpdatedEvent", "evt_i", "{\"tenantId\":\"tenant_01\"}"),
            record("PersonCreatedEvent", "evt_j", "{\"tenantId\":\"tenant_01\"}")
        ));
        CapturingRabbitMqPublisher rabbit = new CapturingRabbitMqPublisher();
        OutboxPublisher publisher = new OutboxPublisher(
            store, rabbit, new ObjectMapper(),
            new MeetingApiMetrics(new SimpleMeterRegistry()), 100, 5
        );

        int published = publisher.publishPending("tenant_01");

        assertThat(published).isZero();
        assertThat(rabbit.messages).isEmpty();
        assertThat(store.skippedReasons).hasSize(10);
        assertThat(store.publishedIds).isEmpty();
        assertThat(store.failedIds).isEmpty();
        assertThat(store.unroutableIds).isEmpty();
    }

    @Test
    void publishPendingMarksMalformedProcessingTaskAsFailed() {
        // ProcessingTaskCreatedEvent payloads missing required fields
        // (taskId / tenantId / etc) must be caught by
        // the preflight validator and marked FAILED rather than
        // shipped to RabbitMQ where ai-worker would have to reject
        // them — by which time a malformed callback could already be
        // routed against an unknown task.
        FakeStore store = new FakeStore(List.of(
            record("ProcessingTaskCreatedEvent", "evt_bad",
                "{\"pipelineSteps\":[\"RAG_INDEXING\"]}")
        ));
        CapturingRabbitMqPublisher rabbit = new CapturingRabbitMqPublisher();
        OutboxPublisher publisher = new OutboxPublisher(
            store, rabbit, new ObjectMapper(),
            new MeetingApiMetrics(new SimpleMeterRegistry()), 100, 5
        );

        int published = publisher.publishPending("tenant_01");

        assertThat(published).isZero();
        assertThat(rabbit.messages).isEmpty();
        assertThat(store.failedIds).containsExactly("evt_bad");
        assertThat(store.publishedIds).isEmpty();
    }

    @Test
    void publishPendingMarksUnknownEventTypesAsUnroutable() {
        FakeStore store = new FakeStore(List.of(
            record("MysteryEvent_v99", "evt_mystery", "{}")
        ));
        CapturingRabbitMqPublisher rabbit = new CapturingRabbitMqPublisher();
        OutboxPublisher publisher = new OutboxPublisher(
            store, rabbit, new ObjectMapper(),
            new MeetingApiMetrics(new SimpleMeterRegistry()), 100, 5
        );

        int published = publisher.publishPending("tenant_01");

        assertThat(published).isZero();
        assertThat(rabbit.messages).isEmpty();
        assertThat(store.unroutableIds).containsExactly("evt_mystery");
        assertThat(store.unroutableReasons.get("evt_mystery")).contains("MysteryEvent_v99");
    }

    @Test
    void publishPendingRoutesAllowListedEvents() {
        FakeStore store = new FakeStore(List.of(
            record("ProcessingTaskCreatedEvent", "evt_proc",
                // TEXT_EMBEDDING is contract-valid only with a meetingId or
                // documentId (schema allOf[2]); include meetingId so the
                // preflight validator's per-taskType conditional passes.
                "{\"taskId\":\"task_x\",\"taskType\":\"TEXT_EMBEDDING\","
                    + "\"tenantId\":\"tenant_01\",\"meetingId\":\"mtg_01\","
                    + "\"attemptNo\":1,\"pipelineSteps\":[\"RAG_INDEXING\"],"
                    + "\"expectedInputVersion\":{\"chunkStrategyVersion\":\"v1\"},"
                    + "\"options\":{},\"traceId\":\"trace_x\"}"),
            record("ExportJobCreatedEvent", "evt_export",
                "{\"tenantId\":\"tenant_01\",\"exportId\":\"exp_x\","
                    + "\"meetingId\":\"mtg_01\",\"format\":\"PDF\","
                    + "\"traceId\":\"trace_x\","
                    + "\"expectedInputVersion\":{\"transcriptVersion\":1}}")
        ));
        CapturingRabbitMqPublisher rabbit = new CapturingRabbitMqPublisher();
        OutboxPublisher publisher = new OutboxPublisher(
            store, rabbit, new ObjectMapper(),
            new MeetingApiMetrics(new SimpleMeterRegistry()), 100, 5
        );

        int published = publisher.publishPending("tenant_01");

        assertThat(published).isEqualTo(2);
        assertThat(rabbit.messages).extracting(PublishedMessage::routingKey)
            .containsExactly("task.embed", "task.export");
        assertThat(store.publishedIds).hasSize(2);
        assertThat(store.skippedReasons).isEmpty();
        assertThat(store.unroutableIds).isEmpty();
    }

    private static OutboxPublisher newPublisher() {
        return new OutboxPublisher(
            new FakeStore(List.of()),
            new CapturingRabbitMqPublisher(),
            new ObjectMapper(),
            new MeetingApiMetrics(new SimpleMeterRegistry()),
            100,
            5
        );
    }

    private static OutboxEventRecord record(String eventType, String payloadJson) {
        return record(eventType, "evt_test", payloadJson);
    }

    private static OutboxEventRecord record(String eventType, String id, String payloadJson) {
        return new OutboxEventRecord(
            id, "tenant_01", "ProcessingTask", "task_01",
            1, eventType, payloadJson, "dedupe", 0, NOW
        );
    }

    /**
     * In-memory test double — Mockito would work, but we want to
     * assert by which terminal method was called for each row and
     * keep that readable.
     */
    private static final class FakeStore extends OutboxEventStore {
        private final List<OutboxEventRecord> rows;
        final List<String> publishedIds = new ArrayList<>();
        final List<String> failedIds = new ArrayList<>();
        final List<String> unroutableIds = new ArrayList<>();
        final Map<String, String> unroutableReasons = new HashMap<>();
        final Map<String, String> skippedReasons = new HashMap<>();

        FakeStore(List<OutboxEventRecord> rows) {
            super(null, new ObjectMapper());
            this.rows = rows;
        }

        @Override
        public List<OutboxEventRecord> lockPendingBatch(String tenantId, int batchSize) {
            return rows;
        }

        @Override
        public void markPublished(String id) {
            publishedIds.add(id);
        }

        @Override
        public void markFailed(String id, String errorCode, String errorMessage, int maxRetries) {
            failedIds.add(id);
        }

        @Override
        public void markSkipped(String id, String reason) {
            skippedReasons.put(id, reason);
        }

        @Override
        public void markUnroutable(String id, String reason) {
            unroutableIds.add(id);
            unroutableReasons.put(id, reason);
        }
    }

    private static final class CapturingRabbitMqPublisher extends RabbitMqPublisher {
        private final List<PublishedMessage> messages = new ArrayList<>();

        CapturingRabbitMqPublisher() {
            super(new RabbitMqProperties("localhost", 5672, "guest", "guest", "/"));
        }

        @Override
        public void publish(String routingKey, String payloadJson, Map<String, Object> headers) {
            messages.add(new PublishedMessage(routingKey, payloadJson, headers));
        }
    }

    private record PublishedMessage(String routingKey, String payloadJson, Map<String, Object> headers) {
    }
}
