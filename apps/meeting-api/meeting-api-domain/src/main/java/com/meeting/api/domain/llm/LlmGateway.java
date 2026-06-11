package com.meeting.api.domain.llm;

import java.util.Map;

/**
 * Synchronous gateway to a third-party LLM provider (DashScope, OpenAI-compatible).
 *
 * Implementations MUST:
 * <ul>
 *   <li>Resolve the prompt template by {@code taskName}, apply variables, audit the call.</li>
 *   <li>Hash input/output, capture token counts and latency, and write to {@code llm_call_logs}.</li>
 *   <li>Never log or persist plaintext audio/embedding/PII content beyond what the template renders.</li>
 * </ul>
 */
public interface LlmGateway {
    LlmResponse complete(LlmRequest request);

    record LlmRequest(
        String tenantId,
        String meetingId,
        String taskId,
        String capability,
        String taskName,
        Map<String, Object> variables,
        String expectedJsonSchema,
        String traceId
    ) {
    }

    record LlmResponse(
        String content,
        String structuredJson,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String modelVersion,
        String llmCallLogId,
        String artifactManifestId
    ) {
    }
}
