package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.infrastructure.mq.ExportJobMessageValidator;
import com.meeting.api.infrastructure.mq.ExportJobMessageValidator.InvalidPayloadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 final-check.md C3 — ExportJobMessageValidator unit tests.
 *
 * <p>Verifies the validator enforces the export-job-message schema
 * shape (required fields + types + format enum). The OutboxPublisher
 * calls this on every {@code ExportJobCreatedEvent} payload before
 * handing it to RabbitMQ, so a producer regression that drops
 * {@code traceId} or sends a typo'd format value is caught here and
 * the row is marked FAILED instead of poisoning the queue.
 */
class ExportJobMessageValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String VALID = """
        {
          "tenantId": "tenant_01",
          "exportId": "exp_01",
          "meetingId": "mtg_01",
          "format": "PDF",
          "expectedInputVersion": {"transcriptVersion": 3, "minutesVersion": 1},
          "traceId": "trace_01",
          "createdAt": "2026-05-19T10:00:00Z"
        }
        """;

    @Test
    void acceptsContractCompliantPayload() {
        assertThatCode(() -> ExportJobMessageValidator.INSTANCE.validate(VALID, mapper))
            .doesNotThrowAnyException();
    }

    @Test
    void acceptsPayloadWithoutOptionalCreatedAt() {
        String missingCreatedAt = """
            {
              "tenantId": "t",
              "exportId": "exp",
              "meetingId": "mtg",
              "format": "DOCX",
              "expectedInputVersion": {"transcriptVersion": 0},
              "traceId": "tr"
            }
            """;
        assertThatCode(() -> ExportJobMessageValidator.INSTANCE.validate(missingCreatedAt, mapper))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankPayload() {
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate("", mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate("{not json", mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsMissingTraceId() {
        String missingTrace = VALID.replace("\"traceId\": \"trace_01\",", "");
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate(missingTrace, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("traceId");
    }

    @Test
    void rejectsMissingTenantId() {
        String missing = VALID.replace("\"tenantId\": \"tenant_01\",", "");
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate(missing, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsInvalidFormatEnum() {
        String invalidFormat = VALID.replace("\"PDF\"", "\"PROTOBUF\"");
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate(invalidFormat, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("MARKDOWN/DOCX/PDF");
    }

    @Test
    void rejectsMissingExpectedInputVersionObject() {
        String stripped = VALID.replace(
            "\"expectedInputVersion\": {\"transcriptVersion\": 3, \"minutesVersion\": 1},",
            ""
        );
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate(stripped, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("expectedInputVersion");
    }

    @Test
    void rejectsNegativeTranscriptVersion() {
        String negative = VALID.replace("\"transcriptVersion\": 3", "\"transcriptVersion\": -1");
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate(negative, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("transcriptVersion");
    }

    @Test
    void rejectsUnknownTopLevelField() {
        String withExtra = VALID.replace(
            "\"createdAt\": \"2026-05-19T10:00:00Z\"",
            "\"createdAt\": \"2026-05-19T10:00:00Z\", \"audioId\": \"aud_99\""
        );
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate(withExtra, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("additionalProperties");
    }

    @Test
    void rejectsMalformedCreatedAt() {
        String bad = VALID.replace("\"2026-05-19T10:00:00Z\"", "\"yesterday\"");
        assertThatThrownBy(() -> ExportJobMessageValidator.INSTANCE.validate(bad, mapper))
            .isInstanceOf(InvalidPayloadException.class)
            .hasMessageContaining("createdAt");
    }
}
