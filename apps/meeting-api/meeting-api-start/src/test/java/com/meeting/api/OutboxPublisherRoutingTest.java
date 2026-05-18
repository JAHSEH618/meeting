package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.infrastructure.mq.OutboxEventRecord;
import com.meeting.api.infrastructure.mq.OutboxEventStore;
import com.meeting.api.infrastructure.mq.OutboxPublisher;
import com.meeting.api.infrastructure.mq.RabbitMqPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

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
    void missingPipelineStepsFallsBackToAudioCpu() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ProcessingTaskCreatedEvent",
            "{\"taskType\":\"MEETING_FULL_PIPELINE\"}");

        assertThat(publisher.routingKey(record)).isEqualTo("task.audio-cpu");
    }

    @Test
    void unknownStepInPipelineFallsBackToAudioCpu() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("ProcessingTaskCreatedEvent",
            "{\"pipelineSteps\":[\"AUDIO_UPLOAD\"]}");

        // AUDIO_UPLOAD is Java-owned and not routable — fallback applies.
        assertThat(publisher.routingKey(record)).isEqualTo("task.audio-cpu");
    }

    @Test
    void workerPhaseEventStillRoutesToLlmQueue() {
        OutboxPublisher publisher = newPublisher();
        OutboxEventRecord record = record("WorkerPhaseCompletedEvent", "{}");
        assertThat(publisher.routingKey(record)).isEqualTo("task.llm");
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

    private static OutboxPublisher newPublisher() {
        return new OutboxPublisher(
            Mockito.mock(OutboxEventStore.class),
            Mockito.mock(RabbitMqPublisher.class),
            new ObjectMapper(),
            new MeetingApiMetrics(new SimpleMeterRegistry()),
            100,
            5
        );
    }

    private static OutboxEventRecord record(String eventType, String payloadJson) {
        return new OutboxEventRecord(
            "evt_test", "tenant_01", "ProcessingTask", "task_01",
            1, eventType, payloadJson, "dedupe", 0, NOW
        );
    }
}
