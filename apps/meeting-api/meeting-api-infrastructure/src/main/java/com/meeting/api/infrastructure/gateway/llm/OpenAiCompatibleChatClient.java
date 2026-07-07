package com.meeting.api.infrastructure.gateway.llm;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.llm.LlmProviderException;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible chat client port. DashScope and OpenAI both speak this.
 * Implementations are responsible for HTTP retries, timeouts, and provider-specific auth headers.
 */
public interface OpenAiCompatibleChatClient {
    ChatCompletion chatComplete(ChatCompletionRequest request);

    record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        String responseFormatJsonSchema,
        Double topP
    ) {
        public ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            Integer maxTokens,
            String responseFormatJsonSchema
        ) {
            this(model, messages, temperature, maxTokens, responseFormatJsonSchema, null);
        }
    }

    record ChatMessage(String role, String content) {
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }
    }

    record ChatCompletion(
        String content,
        String modelVersion,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        Map<String, Object> rawResponse,
        String finishReason
    ) {
        public ChatCompletion(
            String content,
            String modelVersion,
            int promptTokens,
            int completionTokens,
            long latencyMs,
            Map<String, Object> rawResponse
        ) {
            this(content, modelVersion, promptTokens, completionTokens, latencyMs, rawResponse, null);
        }
    }

    /**
     * Translate transport/protocol errors into {@link LlmProviderException}.
     */
    static LlmProviderException timeout(Throwable cause) {
        return new LlmProviderException(ErrorCode.LLM_PROVIDER_TIMEOUT, "LLM provider timeout", cause);
    }

    static LlmProviderException rateLimit(Throwable cause) {
        return new LlmProviderException(ErrorCode.LLM_RATE_LIMIT, "LLM provider rate limit", cause);
    }

    static LlmProviderException unexpected(Throwable cause) {
        return new LlmProviderException(ErrorCode.LLM_PROVIDER_TIMEOUT, "LLM provider error: " + cause.getMessage(), cause);
    }
}
