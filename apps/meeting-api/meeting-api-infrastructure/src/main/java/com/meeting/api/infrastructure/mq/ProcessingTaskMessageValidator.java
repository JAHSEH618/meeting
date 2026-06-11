package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.enums.ProcessingStep;
import java.util.Set;

/**
 * Last-mile shape guard for {@code ProcessingTaskCreatedEvent} payloads
 * the outbox publisher hands to RabbitMQ.
 *
 * <p>The contract source of truth is
 * {@code packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json}.
 * Until this validator existed, {@link OutboxPublisher} only checked
 * {@code pipelineSteps} (via routing-key resolution); a payload missing
 * {@code taskId} / {@code tenantId} would be marked PUBLISHED and only
 * ai-worker's fail-fast would reject it — but by then any malformed
 * callback could already be routed against an unknown task/tenant.
 *
 * <p>This validator checks the required top-level fields, the enums
 * Java owns ({@code taskType}, {@code pipelineSteps} including the
 * no-Java-owned-steps invariant), and the
 * {@code expectedInputVersion.chunkStrategyVersion} requirement that
 * downstream chunkers depend on. A failure leaves the outbox row in
 * PENDING and the call site marks it FAILED with
 * {@code OUTBOX_PUBLISH_FAILED}.
 */
public final class ProcessingTaskMessageValidator {

    public static final ProcessingTaskMessageValidator INSTANCE = new ProcessingTaskMessageValidator();

    private static final Set<String> ALLOWED_TASK_TYPES = Set.of(
        "MEETING_FULL_PIPELINE",
        "SPEAKER_ENROLLMENT",
        "TEXT_EMBEDDING",
        "RAG_REINDEX"
    );

    /**
     * Steps Java owns end-to-end — the worker MUST NOT see them in
     * {@code pipelineSteps}. Mirrors the constraint in
     * {@code processing-task-message.schema.json} and the fail-fast
     * branch in ai-worker.
     */
    private static final Set<ProcessingStep> JAVA_OWNED_STEPS = Set.of(
        ProcessingStep.AUDIO_UPLOAD,
        ProcessingStep.SUMMARY,
        ProcessingStep.EXTRACTION,
        ProcessingStep.EXPORT
    );

    private ProcessingTaskMessageValidator() {
    }

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

        requireNonBlankString(root, "taskId");
        requireTaskType(root.get("taskType"));
        requireNonBlankString(root, "tenantId");
        requireAttemptNo(root.get("attemptNo"));
        requirePipelineSteps(root.get("pipelineSteps"));
        requireExpectedInputVersion(root.get("expectedInputVersion"));
        requireOptionsObject(root.get("options"));
        requireNonBlankString(root, "traceId");
    }

    private static void requireNonBlankString(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new InvalidPayloadException(field + " is required and must be a non-blank string");
        }
    }

    private static void requireTaskType(JsonNode node) {
        if (node == null || !node.isTextual() || !ALLOWED_TASK_TYPES.contains(node.asText())) {
            throw new InvalidPayloadException(
                "taskType is required and must be one of " + ALLOWED_TASK_TYPES
                    + ", got: " + (node == null ? "<missing>" : node.asText())
            );
        }
    }

    private static void requireAttemptNo(JsonNode node) {
        if (node == null || !node.canConvertToInt() || node.asInt() < 1) {
            throw new InvalidPayloadException(
                "attemptNo is required and must be an integer >= 1"
            );
        }
    }

    private static void requirePipelineSteps(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new InvalidPayloadException(
                "pipelineSteps is required and must be a non-empty array"
            );
        }
        for (JsonNode entry : node) {
            if (!entry.isTextual()) {
                throw new InvalidPayloadException("pipelineSteps must contain only strings");
            }
            ProcessingStep step;
            try {
                step = ProcessingStep.valueOf(entry.asText());
            } catch (IllegalArgumentException ex) {
                throw new InvalidPayloadException(
                    "pipelineSteps contains unknown step: " + entry.asText()
                );
            }
            if (JAVA_OWNED_STEPS.contains(step)) {
                throw new InvalidPayloadException(
                    "pipelineSteps contains Java-owned step " + step
                        + " — worker must never see AUDIO_UPLOAD/SUMMARY/EXTRACTION/EXPORT"
                );
            }
        }
    }

    private static void requireExpectedInputVersion(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidPayloadException(
                "expectedInputVersion is required and must be an object"
            );
        }
        JsonNode csv = node.get("chunkStrategyVersion");
        if (csv == null || !csv.isTextual() || csv.asText().isBlank()) {
            throw new InvalidPayloadException(
                "expectedInputVersion.chunkStrategyVersion is required and must be a non-blank string"
            );
        }
    }

    private static void requireOptionsObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidPayloadException("options is required and must be an object");
        }
    }

    public static final class InvalidPayloadException extends RuntimeException {
        public InvalidPayloadException(String message) {
            super(message);
        }
    }
}
