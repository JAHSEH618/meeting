package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.infrastructure.mq.ProcessingTaskMessageValidator;
import com.meeting.api.infrastructure.mq.ProcessingTaskMessageValidator.InvalidPayloadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Final-check follow-up — preflight schema gate for
 * {@code ProcessingTaskCreatedEvent} payloads published to RabbitMQ.
 *
 * <p>Until now the outbox publisher only checked {@code pipelineSteps}
 * (via routingKey resolution) before publishing — a payload missing
 * {@code taskId} / {@code tenantId} would be
 * marked PUBLISHED and only ai-worker would reject it, by which point
 * the callback path may end up routing to an unknown task/tenant.
 */
class ProcessingTaskMessageValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // A TEXT_EMBEDDING message is only contract-compliant when it carries
    // at least one of meetingId / documentId (schema allOf[2] anyOf). The
    // meetingId below keeps this fixture valid against the per-taskType
    // conditional gate the validator now enforces.
    private static final String VALID = """
        {
          "taskId": "task_01",
          "taskType": "TEXT_EMBEDDING",
          "tenantId": "tenant_01",
          "meetingId": "meeting_01",
          "attemptNo": 1,
          "pipelineSteps": ["RAG_INDEXING"],
          "expectedInputVersion": {"chunkStrategyVersion": "v1"},
          "options": {},
          "traceId": "trace_01"
        }
        """;

    @Test
    void acceptsContractCompliantPayload() {
        assertThatCode(() -> ProcessingTaskMessageValidator.INSTANCE.validate(VALID, mapper))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankPayload() {
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate("", mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate("{not json", mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsMissingTaskId() {
        String missing = VALID.replace("\"taskId\": \"task_01\",", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(missing, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("taskId");
    }

    @Test
    void rejectsMissingTenantId() {
        String missing = VALID.replace("\"tenantId\": \"tenant_01\",", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(missing, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsMissingTraceId() {
        String missing = VALID.replace("\"traceId\": \"trace_01\"", "\"traceId\": \"\"");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(missing, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("traceId");
    }

    @Test
    void rejectsUnknownTaskType() {
        String bad = VALID.replace("\"TEXT_EMBEDDING\"", "\"BOGUS\"");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("taskType");
    }

    @Test
    void rejectsAttemptNoBelowOne() {
        String bad = VALID.replace("\"attemptNo\": 1", "\"attemptNo\": 0");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("attemptNo");
    }

    @Test
    void rejectsEmptyPipelineSteps() {
        String bad = VALID.replace("[\"RAG_INDEXING\"]", "[]");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("pipelineSteps");
    }

    @Test
    void rejectsJavaOwnedPipelineStep() {
        // contract: pipelineSteps must NOT include Java-owned steps
        // (AUDIO_UPLOAD / SUMMARY / EXTRACTION / EXPORT). ai-worker
        // fail-fast rejects, but we want the same gate in-process.
        String bad = VALID.replace("[\"RAG_INDEXING\"]", "[\"RAG_INDEXING\",\"SUMMARY\"]");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("SUMMARY");
    }

    @Test
    void rejectsMissingExpectedInputVersion() {
        String bad = VALID.replace(
            "\"expectedInputVersion\": {\"chunkStrategyVersion\": \"v1\"},", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("expectedInputVersion");
    }

    @Test
    void rejectsExpectedInputVersionMissingChunkStrategyVersion() {
        String bad = VALID.replace(
            "{\"chunkStrategyVersion\": \"v1\"}", "{}");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("chunkStrategyVersion");
    }

    @Test
    void rejectsMissingOptionsObject() {
        String bad = VALID.replace("\"options\": {},", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("options");
    }

    // ── per-taskType conditional gate (schema allOf if/then) ──────────────

    // A fully-populated MEETING_FULL_PIPELINE message — mirrors what
    // createForCompletedAudioUpload() / phase2TaskMessagePayload() produce.
    private static final String VALID_FULL_PIPELINE = """
        {
          "taskId": "task_fp",
          "taskType": "MEETING_FULL_PIPELINE",
          "tenantId": "tenant_01",
          "meetingId": "meeting_01",
          "audioFileId": "file_01",
          "audioUri": "tos://meeting-audio-auska/audio_01.wav",
          "language": "zh",
          "channelMap": {"channelCount": 1, "layout": "mono"},
          "knownParticipants": [],
          "minSpeakers": 1,
          "maxSpeakers": 4,
          "attemptNo": 1,
          "pipelineSteps": ["AUDIO_PREPROCESS","ASR"],
          "expectedInputVersion": {"chunkStrategyVersion": "v1"},
          "options": {},
          "traceId": "trace_fp"
        }
        """;

    @Test
    void acceptsFullyPopulatedMeetingFullPipeline() {
        assertThatCode(() -> ProcessingTaskMessageValidator.INSTANCE.validate(VALID_FULL_PIPELINE, mapper))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsMeetingFullPipelineMissingAudioFileId() {
        // This is exactly the MVP0 create() drift: a MEETING_FULL_PIPELINE
        // message with no uploaded-audio context. It must fail fast at the
        // Java publish boundary instead of being rejected by the worker.
        String bad = VALID_FULL_PIPELINE.replace("\"audioFileId\": \"file_01\",", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("audioFileId");
    }

    @Test
    void rejectsMeetingFullPipelineMissingAudioUri() {
        String bad = VALID_FULL_PIPELINE.replace(
            "\"audioUri\": \"tos://meeting-audio-auska/audio_01.wav\",", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("audioUri");
    }

    @Test
    void rejectsMeetingFullPipelineNonTosAudioUri() {
        String bad = VALID_FULL_PIPELINE.replace(
            "tos://meeting-audio-auska/audio_01.wav", "s3://bucket/audio_01.wav");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("audioUri");
    }

    @Test
    void rejectsMeetingFullPipelineMissingLanguage() {
        String bad = VALID_FULL_PIPELINE.replace("\"language\": \"zh\",", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("language");
    }

    @Test
    void rejectsMeetingFullPipelineMissingMinMaxSpeakers() {
        String bad = VALID_FULL_PIPELINE.replace("\"minSpeakers\": 1,", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("minSpeakers");
    }

    private static final String VALID_SPEAKER_ENROLLMENT = """
        {
          "taskId": "task_se",
          "taskType": "SPEAKER_ENROLLMENT",
          "tenantId": "tenant_01",
          "speakerProfileId": "spk_01",
          "speakerEnrollmentId": "spe_01",
          "audioFileId": "file_01",
          "audioUri": "tos://meeting-audio-auska/tenant_01/spe_01.wav",
          "language": "zh",
          "attemptNo": 1,
          "pipelineSteps": ["SPEAKER_EMBEDDING","SPEAKER_MATCHING"],
          "expectedInputVersion": {"chunkStrategyVersion": "v1", "embeddingModelVersion": "v1"},
          "options": {},
          "traceId": "trace_se"
        }
        """;

    @Test
    void acceptsFullyPopulatedSpeakerEnrollment() {
        assertThatCode(() -> ProcessingTaskMessageValidator.INSTANCE.validate(VALID_SPEAKER_ENROLLMENT, mapper))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsSpeakerEnrollmentMissingSpeakerProfileId() {
        String bad = VALID_SPEAKER_ENROLLMENT.replace("\"speakerProfileId\": \"spk_01\",", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("speakerProfileId");
    }

    @Test
    void rejectsTextEmbeddingWithoutMeetingOrDocumentId() {
        // schema allOf[2]: TEXT_EMBEDDING/RAG_REINDEX require meetingId OR documentId.
        String bad = VALID.replace("\"meetingId\": \"meeting_01\",", "");
        assertThatThrownBy(() -> ProcessingTaskMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("meetingId");
    }

    @Test
    void acceptsTextEmbeddingWithDocumentIdOnly() {
        String ok = VALID.replace("\"meetingId\": \"meeting_01\",", "\"documentId\": \"doc_01\",");
        assertThatCode(() -> ProcessingTaskMessageValidator.INSTANCE.validate(ok, mapper))
            .doesNotThrowAnyException();
    }

    @Test
    void acceptsRagReindexWithMeetingId() {
        String ok = VALID.replace("\"TEXT_EMBEDDING\"", "\"RAG_REINDEX\"");
        assertThatCode(() -> ProcessingTaskMessageValidator.INSTANCE.validate(ok, mapper))
            .doesNotThrowAnyException();
    }
}
