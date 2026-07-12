package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.domain.rag.RerankGateway;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerCircuitBreaker;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalClient;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalProperties;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerRerankGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiWorkerRerankGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiWorkerInternalProperties PROPS = new AiWorkerInternalProperties(
        "http://test-ai-worker", "test-hmac", 3000, 3000, 5000, 2000, 1000, null, null
    );

    @Test
    void rerankSerializesCandidatesAndParsesItems() {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "bge-reranker-v2-m3-fake-v0");
        var items = data.putArray("items");
        items.addObject().put("chunkId", "c1").put("rank", 1).put("rerankScore", 0.95);
        items.addObject().put("chunkId", "c2").put("rank", 2).put("rerankScore", 0.80);

        CapturingClient client = new CapturingClient(data);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(registry)
        );

        RerankGateway.RerankResult result = gateway.rerank(new RerankGateway.RerankRequest(
            "tenant_01",
            "Q3 budget?",
            List.of(
                new RerankGateway.RerankCandidate("c1", "PRIMARY_TRANSCRIPT", "txt1", 0.9, 1),
                new RerankGateway.RerankCandidate("c2", "DOCUMENT", "txt2", 0.7, null)
            ),
            5,
            "bge-reranker-v2-m3-v1",
            "req_r",
            "trace_r"
        ));

        assertThat(client.path).isEqualTo("/rerank");
        assertThat(client.timeoutMs).isEqualTo(3000);
        assertThat(client.body.path("query").asText()).isEqualTo("Q3 budget?");
        assertThat(client.body.path("topN").asInt()).isEqualTo(5);
        assertThat(client.body.path("modelVersion").asText()).isEqualTo("bge-reranker-v2-m3-v1");
        var sentCandidates = client.body.path("candidates");
        assertThat(sentCandidates.size()).isEqualTo(2);
        assertThat(sentCandidates.get(0).path("sourceVersion").asInt()).isEqualTo(1);
        // Candidate 2 had null sourceVersion → field MUST NOT be present.
        assertThat(sentCandidates.get(1).has("sourceVersion")).isFalse();

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).chunkId()).isEqualTo("c1");
        assertThat(result.items().get(0).rank()).isEqualTo(1);
        assertThat(result.items().get(0).rerankScore()).isEqualTo(0.95);

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "rerank", "outcome", "success").count())
            .isEqualTo(1.0);
    }

    @Test
    void rerankRejectsItemWithInvalidRank() {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "bge-reranker-v2-m3-fake-v0");
        data.putArray("items").addObject().put("chunkId", "c1").put("rank", 0).put("rerankScore", 0.9);

        CapturingClient client = new CapturingClient(data);
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(new SimpleMeterRegistry())
        );

        assertThatThrownBy(() -> gateway.rerank(req()))
            .isInstanceOf(AiWorkerContractException.class)
            .hasMessageContaining("rank");
    }

    @Test
    void rerankRejectsItemMissingChunkId() {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "bge-reranker-v2-m3-fake-v0");
        data.putArray("items").addObject().put("rank", 1).put("rerankScore", 0.9);

        CapturingClient client = new CapturingClient(data);
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(new SimpleMeterRegistry())
        );

        assertThatThrownBy(() -> gateway.rerank(req()))
            .isInstanceOf(AiWorkerContractException.class)
            .hasMessageContaining("chunkId");
    }

    @Test
    void rerankRejectsResponseMissingItemsArray() {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "bge-reranker-v2-m3-fake-v0");

        CapturingClient client = new CapturingClient(data);
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(new SimpleMeterRegistry())
        );

        assertThatThrownBy(() -> gateway.rerank(req()))
            .isInstanceOf(AiWorkerContractException.class)
            .hasMessageContaining("items");
    }

    @Test
    void unavailableExceptionIsCountedSeparately() {
        AiWorkerInternalClient failing = new AiWorkerInternalClient() {
            @Override
            public JsonNode call(String method, String path, JsonNode body, String tenantId, String requestId, String traceId, int timeoutMs) {
                throw new AiWorkerUnavailableException("RERANK_UNAVAILABLE", "model down");
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            failing, PROPS, MAPPER, new MeetingApiMetrics(registry)
        );

        assertThatThrownBy(() -> gateway.rerank(req()))
            .isInstanceOf(AiWorkerUnavailableException.class);

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "rerank", "outcome", "unavailable").count())
            .isEqualTo(1.0);
    }

    @Test
    void breakerShortCircuitsAfterConsecutiveFailuresWithoutCallingClient() {
        CountingFailingClient failing = new CountingFailingClient();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong nanos = new AtomicLong(0);
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            failing, PROPS, MAPPER, new MeetingApiMetrics(registry),
            new AiWorkerCircuitBreaker(2, Duration.ofSeconds(30), nanos::get)
        );

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> gateway.rerank(req()))
                .isInstanceOf(AiWorkerUnavailableException.class);
        }
        assertThat(failing.calls).isEqualTo(2);

        // Circuit is open: no client call, distinct error code + metric.
        assertThatThrownBy(() -> gateway.rerank(req()))
            .isInstanceOf(AiWorkerUnavailableException.class)
            .hasMessageContaining("circuit");
        assertThat(failing.calls).isEqualTo(2);
        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "rerank", "outcome", "circuit_open").count())
            .isEqualTo(1.0);
    }

    @Test
    void breakerAllowsProbeAfterCooldownAndClosesOnSuccess() {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "v");
        data.putArray("items").addObject().put("chunkId", "c1").put("rank", 1).put("rerankScore", 0.9);

        AtomicLong nanos = new AtomicLong(0);
        FlakyClient client = new FlakyClient(data, 2);
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(new SimpleMeterRegistry()),
            new AiWorkerCircuitBreaker(2, Duration.ofSeconds(30), nanos::get)
        );

        assertThatThrownBy(() -> gateway.rerank(req())).isInstanceOf(AiWorkerUnavailableException.class);
        assertThatThrownBy(() -> gateway.rerank(req())).isInstanceOf(AiWorkerUnavailableException.class);
        assertThatThrownBy(() -> gateway.rerank(req())).isInstanceOf(AiWorkerUnavailableException.class);
        assertThat(client.calls).isEqualTo(2);

        // Cooldown elapses → single probe goes through and succeeds → closed.
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(gateway.rerank(req()).items()).hasSize(1);
        assertThat(gateway.rerank(req()).items()).hasSize(1);
        assertThat(client.calls).isEqualTo(4);
    }

    @Test
    void contractErrorDoesNotTripTheBreaker() {
        var badData = MAPPER.createObjectNode();
        badData.put("modelVersion", "v");
        // Missing items → contract error on every call.
        CapturingClient client = new CapturingClient(badData);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong nanos = new AtomicLong(0);
        AiWorkerRerankGateway gateway = new AiWorkerRerankGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(registry),
            new AiWorkerCircuitBreaker(2, Duration.ofSeconds(30), nanos::get)
        );

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gateway.rerank(req()))
                .isInstanceOf(AiWorkerContractException.class);
        }
        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "rerank", "outcome", "circuit_open").count())
            .isEqualTo(0.0);
    }

    private static RerankGateway.RerankRequest req() {
        return new RerankGateway.RerankRequest(
            "tenant_01",
            "q",
            List.of(new RerankGateway.RerankCandidate("c1", "DOCUMENT", "t", 0.5, null)),
            1,
            "v1",
            "req_x",
            "trace_x"
        );
    }

    static class CountingFailingClient implements AiWorkerInternalClient {
        int calls;

        @Override
        public JsonNode call(String method, String path, JsonNode body, String tenantId, String requestId, String traceId, int timeoutMs) {
            calls++;
            throw new AiWorkerUnavailableException("RERANK_UNAVAILABLE", "model down");
        }
    }

    /** Fails the first {@code failuresBeforeSuccess} calls, then succeeds. */
    static class FlakyClient implements AiWorkerInternalClient {
        int calls;
        private final JsonNode response;
        private final int failuresBeforeSuccess;

        FlakyClient(JsonNode response, int failuresBeforeSuccess) {
            this.response = response;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public JsonNode call(String method, String path, JsonNode body, String tenantId, String requestId, String traceId, int timeoutMs) {
            calls++;
            if (calls <= failuresBeforeSuccess) {
                throw new AiWorkerUnavailableException("RERANK_UNAVAILABLE", "model down");
            }
            return response;
        }
    }

    static class CapturingClient implements AiWorkerInternalClient {
        String method;
        String path;
        JsonNode body;
        int timeoutMs;
        private final JsonNode response;

        CapturingClient(JsonNode response) {
            this.response = response;
        }

        @Override
        public JsonNode call(String method, String path, JsonNode body, String tenantId, String requestId, String traceId, int timeoutMs) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.timeoutMs = timeoutMs;
            return response;
        }
    }
}
