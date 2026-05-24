package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.enums.ExportFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lightweight runtime guard for {@code export-job-message} payloads
 * (final-check.md C3).
 *
 * <p>The contract source of truth is
 * {@code packages/meeting-contracts/schemas/rabbitmq/export-job-message.schema.json}.
 * Codegen verifies the schema's shape at build time, but a producer
 * bug can still emit a payload missing {@code traceId} or
 * {@code expectedInputVersion} — that schema violation would only
 * surface when ai-worker rejects the message, by which time the
 * outbox row is already marked PUBLISHED.
 *
 * <p>This validator runs in-process before
 * {@link OutboxPublisher#publishPending(String) publish}: a failure leaves
 * the outbox row in PENDING (so a future code fix can re-publish) and
 * the call site marks it FAILED with {@code OUTBOX_PUBLISH_FAILED}.
 *
 * <p>The check is intentionally schema-shape-only — it asserts the
 * required fields exist and have the right primitive types. Anything
 * more elaborate would duplicate the JSON Schema validator that
 * already ships in the contracts package; here we just want a
 * crisp last-mile gate.
 */
public final class ExportJobMessageValidator {

    /** Single shared instance — purely functional. */
    public static final ExportJobMessageValidator INSTANCE = new ExportJobMessageValidator();

    private static final Set<String> ALLOWED_TOP_LEVEL_FIELDS = new LinkedHashSet<>(Arrays.asList(
        "tenantId", "exportId", "meetingId", "format",
        "expectedInputVersion", "traceId", "createdAt"
    ));

    private ExportJobMessageValidator() {
    }

    /**
     * Throws {@link InvalidPayloadException} if {@code payloadJson}
     * does not satisfy the {@code export-job-message.schema.json}
     * contract. Otherwise returns silently.
     */
    public void validate(String payloadJson, ObjectMapper objectMapper) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new InvalidPayloadException("payload is blank");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException ex) {
            throw new InvalidPayloadException("payload is not valid JSON: " + ex.getOriginalMessage());
        }
        if (!root.isObject()) {
            throw new InvalidPayloadException("payload root must be an object");
        }

        requireNonBlankString(root, "tenantId");
        requireNonBlankString(root, "exportId");
        requireNonBlankString(root, "meetingId");
        requireFormat(root);
        requireExpectedInputVersion(root.get("expectedInputVersion"));
        requireNonBlankString(root, "traceId");
        requireCreatedAtIfPresent(root.get("createdAt"));
        rejectUnknownFields(root);
    }

    private static void requireNonBlankString(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new InvalidPayloadException(field + " is required and must be a non-blank string");
        }
    }

    private static void requireFormat(JsonNode root) {
        JsonNode n = root.get("format");
        if (n == null || !n.isTextual()) {
            throw new InvalidPayloadException("format is required and must be a string");
        }
        try {
            ExportFormat.valueOf(n.asText());
        } catch (IllegalArgumentException ex) {
            throw new InvalidPayloadException(
                "format must be one of MARKDOWN/DOCX/PDF, got: " + n.asText()
            );
        }
    }

    private static void requireExpectedInputVersion(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidPayloadException("expectedInputVersion is required and must be an object");
        }
        JsonNode tv = node.get("transcriptVersion");
        if (tv == null || !tv.canConvertToInt() || tv.asInt() < 0) {
            throw new InvalidPayloadException(
                "expectedInputVersion.transcriptVersion is required and must be a non-negative integer"
            );
        }
        JsonNode mv = node.get("minutesVersion");
        if (mv != null && !mv.isNull() && (!mv.canConvertToInt() || mv.asInt() < 0)) {
            throw new InvalidPayloadException(
                "expectedInputVersion.minutesVersion must be a non-negative integer when present"
            );
        }
        JsonNode rv = node.get("ragVersion");
        if (rv != null && !rv.isNull() && (!rv.canConvertToInt() || rv.asInt() < 0)) {
            throw new InvalidPayloadException(
                "expectedInputVersion.ragVersion must be a non-negative integer when present"
            );
        }
    }

    private static void requireCreatedAtIfPresent(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isTextual()) {
            throw new InvalidPayloadException("createdAt must be an ISO-8601 string when present");
        }
        try {
            OffsetDateTime.parse(node.asText());
        } catch (DateTimeParseException ex) {
            throw new InvalidPayloadException("createdAt must be ISO-8601 date-time, got: " + node.asText());
        }
    }

    private static void rejectUnknownFields(JsonNode root) {
        var fields = root.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!ALLOWED_TOP_LEVEL_FIELDS.contains(name)) {
                throw new InvalidPayloadException(
                    "unexpected field '" + name + "' — schema is additionalProperties=false"
                );
            }
        }
    }

    /** Raised when an outbox payload violates the contract schema. */
    public static final class InvalidPayloadException extends RuntimeException {
        public InvalidPayloadException(String message) {
            super(message);
        }
    }
}
