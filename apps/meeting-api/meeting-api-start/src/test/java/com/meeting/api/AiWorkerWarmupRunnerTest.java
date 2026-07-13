package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalClient;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalProperties;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerWarmupRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiWorkerWarmupRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiWorkerInternalProperties PROPS = new AiWorkerInternalProperties(
        "http://ai-worker", "secret", 3000, 3000, 5000, 2000, 1000, null, null
    );

    @Test
    void enabledRunnerInvokesWarmupAndCountsSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CapturingClient client = new CapturingClient();

        AiWorkerWarmupRunner runner = new AiWorkerWarmupRunner(
            client, PROPS, new MeetingApiMetrics(registry), true, Executors.newSingleThreadExecutor()
        );

        // Use direct warmup() call instead of going through ApplicationReadyEvent +
        // executor to keep the test deterministic.
        runner.warmup();

        assertThat(client.method).isEqualTo("POST");
        assertThat(client.path).isEqualTo("/models/warmup");
        assertThat(client.body).isNull();
        assertThat(client.requestId).isEqualTo("boot-warmup");
        assertThat(client.timeoutMs).isEqualTo(5000);

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "warmup", "outcome", "called").count())
            .isEqualTo(1.0);
        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "warmup", "outcome", "success").count())
            .isEqualTo(1.0);
    }

    @Test
    void disabledRunnerDoesNotInvokeClient() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CapturingClient client = new CapturingClient();

        AiWorkerWarmupRunner runner = new AiWorkerWarmupRunner(
            client, PROPS, new MeetingApiMetrics(registry), false, Executors.newSingleThreadExecutor()
        );

        // Build a minimal ApplicationReadyEvent and confirm the listener short-circuits.
        runner.onApplicationEvent(mock(ApplicationReadyEvent.class));

        assertThat(client.path).isNull();
        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "warmup", "outcome", "called").count())
            .isEqualTo(0.0);
    }

    @Test
    void unavailableExceptionIsSwallowedAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FailingClient client = new FailingClient(new AiWorkerUnavailableException(
            "AI_WORKER_TIMEOUT", "ai-worker down at boot"
        ));

        AiWorkerWarmupRunner runner = new AiWorkerWarmupRunner(
            client, PROPS, new MeetingApiMetrics(registry), true, Executors.newSingleThreadExecutor()
        );

        // Must not throw — startup MUST NOT depend on ai-worker readiness.
        runner.warmup();

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "warmup", "outcome", "unavailable").count())
            .isEqualTo(1.0);
    }

    @Test
    void contractExceptionIsSwallowedAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FailingClient client = new FailingClient(new AiWorkerContractException(
            "MODELS_AUTH_FAILED", "hmac config drift"
        ));

        AiWorkerWarmupRunner runner = new AiWorkerWarmupRunner(
            client, PROPS, new MeetingApiMetrics(registry), true, Executors.newSingleThreadExecutor()
        );

        runner.warmup();

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "warmup", "outcome", "contract_error").count())
            .isEqualTo(1.0);
    }

    @Test
    void unexpectedExceptionIsSwallowedAndCountedAsError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FailingClient client = new FailingClient(new IllegalStateException("unexpected"));

        AiWorkerWarmupRunner runner = new AiWorkerWarmupRunner(
            client, PROPS, new MeetingApiMetrics(registry), true, Executors.newSingleThreadExecutor()
        );

        runner.warmup();

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "warmup", "outcome", "error").count())
            .isEqualTo(1.0);
    }

    @Test
    void applicationReadyEventTriggersWarmupViaExecutor() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CapturingClient client = new CapturingClient();
        var executor = Executors.newSingleThreadExecutor();

        AiWorkerWarmupRunner runner = new AiWorkerWarmupRunner(
            client, PROPS, new MeetingApiMetrics(registry), true, executor
        );

        runner.onApplicationEvent(mock(ApplicationReadyEvent.class));
        executor.shutdown();
        boolean finished = executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(finished).isTrue();
        assertThat(client.path).isEqualTo("/models/warmup");
    }

    private static class CapturingClient implements AiWorkerInternalClient {
        String method;
        String path;
        JsonNode body;
        String requestId;
        int timeoutMs;

        @Override
        public JsonNode call(String method, String path, JsonNode body, String tenantId, String requestId, String traceId, int timeoutMs) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.requestId = requestId;
            this.timeoutMs = timeoutMs;
            return MAPPER.createObjectNode().put("triggered", true);
        }
    }

    private static class FailingClient implements AiWorkerInternalClient {
        private final RuntimeException exception;

        FailingClient(RuntimeException exception) {
            this.exception = exception;
        }

        @Override
        public JsonNode call(String method, String path, JsonNode body, String tenantId, String requestId, String traceId, int timeoutMs) {
            throw exception;
        }
    }
}
