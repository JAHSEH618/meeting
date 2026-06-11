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

    private static final String VALID = """
        {
          "taskId": "task_01",
          "taskType": "TEXT_EMBEDDING",
          "tenantId": "tenant_01",
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
}
