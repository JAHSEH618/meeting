package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalClient;
import com.meeting.api.start.health.AiWorkerHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkerHealthIndicatorTest {

    @Test
    void probesRelativeHealthPathBecauseClientAddsInternalPrefix() {
        CapturingClient client = new CapturingClient();

        assertThat(new AiWorkerHealthIndicator(client).health().getStatus())
            .isEqualTo(Status.UP);
        assertThat(client.path).isEqualTo("/health");
    }

    private static final class CapturingClient implements AiWorkerInternalClient {
        private String path;

        @Override
        public JsonNode call(
            String method,
            String path,
            JsonNode body,
            String tenantId,
            String requestId,
            String traceId,
            int timeoutMs
        ) {
            this.path = path;
            return JsonNodeFactory.instance.objectNode();
        }
    }
}
