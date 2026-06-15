package com.meeting.api;

import com.meeting.api.infrastructure.gateway.llm.OpenAiCompatibleChatClient;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class SpringBootTestSupportConfig {

    @Bean
    OpenAiCompatibleChatClient openAiCompatibleChatClient() {
        return request -> new OpenAiCompatibleChatClient.ChatCompletion(
            "{}",
            "test",
            0,
            0,
            0L,
            Map.of()
        );
    }
}
