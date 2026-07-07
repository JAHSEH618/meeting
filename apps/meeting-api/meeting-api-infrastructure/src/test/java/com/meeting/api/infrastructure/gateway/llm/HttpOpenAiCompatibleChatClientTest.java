package com.meeting.api.infrastructure.gateway.llm;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.llm.LlmProviderException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOpenAiCompatibleChatClientTest {

    @Test
    void parseResponseRejectsLengthFinishReasonAsTruncatedOutput() {
        Map<String, Object> response = Map.of(
            "model", "qwen-plus",
            "choices", List.of(Map.of(
                "finish_reason", "length",
                "message", Map.of("content", "{\"partial\":")
            )),
            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 4096)
        );

        assertThatThrownBy(() -> HttpOpenAiCompatibleChatClient.parseResponse(response, 123L))
            .isInstanceOfSatisfying(LlmProviderException.class, ex ->
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.LLM_OUTPUT_TRUNCATED));
    }

    @Test
    void parseResponseCarriesNonLengthFinishReason() {
        Map<String, Object> response = Map.of(
            "model", "qwen-plus",
            "choices", List.of(Map.of(
                "finish_reason", "stop",
                "message", Map.of("content", "{\"ok\":true}")
            )),
            "usage", Map.of("prompt_tokens", 1, "completion_tokens", 2)
        );

        var completion = HttpOpenAiCompatibleChatClient.parseResponse(response, 123L);

        assertThat(completion.content()).isEqualTo("{\"ok\":true}");
        assertThat(completion.finishReason()).isEqualTo("stop");
        assertThat(completion.promptTokens()).isEqualTo(1);
        assertThat(completion.completionTokens()).isEqualTo(2);
    }
}
