package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalProperties;
import com.meeting.api.infrastructure.gateway.aiworker.HttpAiWorkerInternalClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class HttpAiWorkerInternalClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(AiWorkerClientSlice.class);

    @Test
    void wiresHttpAiWorkerInternalClientWithoutRequiringAClockBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HttpAiWorkerInternalClient.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(HttpAiWorkerInternalClient.class)
    static class AiWorkerClientSlice {

        @Bean
        AiWorkerInternalProperties aiWorkerInternalProperties() {
            return new AiWorkerInternalProperties(
                "http://127.0.0.1:8090",
                "test-secret-abc",
                3000,
                3000,
                5000,
                2000,
                1000,
                null,
                null
            );
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
