package com.meeting.api.infrastructure.gateway.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.llm.LlmProviderException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Spring {@link RestClient}-based OpenAI-compatible HTTP client.
 * Used in production to call DashScope's {@code /chat/completions} endpoint.
 *
 * In unit tests the {@code LlmGateway} is exercised against an in-memory fake client; this class
 * is wired only when the active Spring profile is not {@code test}.
 */
@Component
@Profile("!test")
public class HttpOpenAiCompatibleChatClient implements OpenAiCompatibleChatClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpOpenAiCompatibleChatClient(
        @Value("${meeting.llm.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
        @Value("${meeting.llm.dashscope.api-key:}") String apiKey,
        @Value("${meeting.llm.dashscope.timeout-seconds:60}") int timeoutSeconds,
        ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", apiKey == null || apiKey.isBlank() ? "" : "Bearer " + apiKey)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                {
                    setConnectTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
                }
            })
            .build();
    }

    @Override
    public ChatCompletion chatComplete(ChatCompletionRequest request) {
        long started = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", request.messages().stream()
            .map(m -> Map.<String, Object>of("role", m.role(), "content", m.content()))
            .toList());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.responseFormatJsonSchema() != null && !request.responseFormatJsonSchema().isBlank()) {
            body.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of("name", "structured", "schema", parseSchema(request.responseFormatJsonSchema()))
            ));
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                .uri(URI.create("/chat/completions"))
                .body(body)
                .retrieve()
                .body(Map.class);
            long latencyMs = System.currentTimeMillis() - started;
            return parseResponse(response, latencyMs);
        } catch (ResourceAccessException ex) {
            throw OpenAiCompatibleChatClient.timeout(ex);
        } catch (HttpClientErrorException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 429) {
                throw OpenAiCompatibleChatClient.rateLimit(ex);
            }
            throw new LlmProviderException(ErrorCode.LLM_PROVIDER_TIMEOUT, "LLM client error " + status.value() + ": " + ex.getResponseBodyAsString(), ex);
        } catch (HttpServerErrorException ex) {
            throw new LlmProviderException(ErrorCode.LLM_PROVIDER_TIMEOUT, "LLM server error " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw OpenAiCompatibleChatClient.unexpected(ex);
        }
    }

    private Map<String, Object> parseSchema(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static ChatCompletion parseResponse(Map<String, Object> response, long latencyMs) {
        if (response == null) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "empty LLM response body");
        }
        String modelVersion = String.valueOf(response.getOrDefault("model", "unknown"));
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "LLM response missing choices");
        }
        Map<String, Object> firstChoice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        if (message == null) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "LLM response missing message");
        }
        String content = String.valueOf(message.getOrDefault("content", ""));
        Object usageRaw = response.get("usage");
        Map<String, Object> usage = usageRaw instanceof Map ? (Map<String, Object>) usageRaw : Map.of();
        int promptTokens = asInt(usage.get("prompt_tokens"));
        int completionTokens = asInt(usage.get("completion_tokens"));
        return new ChatCompletion(content, modelVersion, promptTokens, completionTokens, latencyMs, response);
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }
}
