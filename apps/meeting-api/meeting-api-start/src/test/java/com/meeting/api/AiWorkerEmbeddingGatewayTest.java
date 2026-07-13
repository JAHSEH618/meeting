package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.domain.rag.EmbeddingGateway;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerEmbeddingGateway;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalClient;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiWorkerEmbeddingGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiWorkerInternalProperties PROPS = new AiWorkerInternalProperties(
        "http://test-ai-worker", "test-hmac", 3000, 3000, 5000, 2000, 1000, null, null
    );

    @Test
    void embedSerializesRequestAndParsesVectors() {
        CapturingClient client = new CapturingClient(buildEmbedData(2, 1024));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiWorkerEmbeddingGateway gateway = new AiWorkerEmbeddingGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(registry)
        );

        EmbeddingGateway.EmbedResult result = gateway.embed(new EmbeddingGateway.EmbedRequest(
            "tenant_01",
            List.of("hello", "world"),
            "req_1",
            "trace_1"
        ));

        assertThat(client.method).isEqualTo("POST");
        assertThat(client.path).isEqualTo("/embed");
        assertThat(client.body.path("tenantId").asText()).isEqualTo("tenant_01");
        assertThat(client.body.path("modelVersion").asText()).isEqualTo("bge-m3-v1");
        assertThat(client.body.path("texts").size()).isEqualTo(2);
        assertThat(client.body.path("texts").get(0).asText()).isEqualTo("hello");
        assertThat(client.tenantId).isEqualTo("tenant_01");
        assertThat(client.requestId).isEqualTo("req_1");
        assertThat(client.traceId).isEqualTo("trace_1");
        assertThat(client.timeoutMs).isEqualTo(3000);

        assertThat(result.dimension()).isEqualTo(1024);
        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)).hasSize(1024);
        assertThat(result.modelVersion()).isEqualTo("bge-m3-fake-v0");

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "embed", "outcome", "called").count())
            .isEqualTo(1.0);
        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "embed", "outcome", "success").count())
            .isEqualTo(1.0);
    }

    @Test
    void embedRejectsResponseMissingDimension() {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "bge-m3-fake-v0");
        data.putArray("vectors").addArray().add(0.1).add(0.2);
        CapturingClient client = new CapturingClient(data);

        AiWorkerEmbeddingGateway gateway = new AiWorkerEmbeddingGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(new SimpleMeterRegistry())
        );

        assertThatThrownBy(() -> gateway.embed(req()))
            .isInstanceOf(AiWorkerContractException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    void embedRejectsVectorWithWrongLength() {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "bge-m3-fake-v0");
        data.put("dimension", 4);
        // Vector length is 3 but dimension claims 4 → contract exception.
        data.putArray("vectors").addArray().add(0.1).add(0.2).add(0.3);
        CapturingClient client = new CapturingClient(data);

        AiWorkerEmbeddingGateway gateway = new AiWorkerEmbeddingGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(new SimpleMeterRegistry())
        );

        assertThatThrownBy(() -> gateway.embed(req()))
            .isInstanceOf(AiWorkerContractException.class)
            .hasMessageContaining("does not match declared dimension");
    }

    @Test
    void unavailableExceptionIsPropagatedAndCounted() {
        FailingClient client = new FailingClient(new AiWorkerUnavailableException(
            "EMBEDDING_UNAVAILABLE", "model not ready"
        ));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiWorkerEmbeddingGateway gateway = new AiWorkerEmbeddingGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(registry)
        );

        assertThatThrownBy(() -> gateway.embed(req()))
            .isInstanceOf(AiWorkerUnavailableException.class);

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "embed", "outcome", "unavailable").count())
            .isEqualTo(1.0);
        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "embed", "outcome", "success").count())
            .isEqualTo(0.0);
    }

    @Test
    void contractExceptionIsPropagatedAndCounted() {
        FailingClient client = new FailingClient(new AiWorkerContractException(
            "EMBEDDING_AUTH_FAILED", "HMAC failure"
        ));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiWorkerEmbeddingGateway gateway = new AiWorkerEmbeddingGateway(
            client, PROPS, MAPPER, new MeetingApiMetrics(registry)
        );

        assertThatThrownBy(() -> gateway.embed(req()))
            .isInstanceOf(AiWorkerContractException.class);

        assertThat(registry.counter("meeting.api.aiworker.calls", "operation", "embed", "outcome", "contract_error").count())
            .isEqualTo(1.0);
    }

    private static EmbeddingGateway.EmbedRequest req() {
        return new EmbeddingGateway.EmbedRequest("tenant_01", List.of("q"), "req_x", "trace_x");
    }

    private static JsonNode buildEmbedData(int count, int dim) {
        var data = MAPPER.createObjectNode();
        data.put("modelVersion", "bge-m3-fake-v0");
        data.put("dimension", dim);
        var vectorsArr = data.putArray("vectors");
        for (int v = 0; v < count; v++) {
            var arr = vectorsArr.addArray();
            for (int i = 0; i < dim; i++) {
                arr.add(((v + 1) * 0.001) + i * 0.0001);
            }
        }
        return data;
    }

    static class CapturingClient implements AiWorkerInternalClient {
        String method;
        String path;
        JsonNode body;
        String tenantId;
        String requestId;
        String traceId;
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
            this.tenantId = tenantId;
            this.requestId = requestId;
            this.traceId = traceId;
            this.timeoutMs = timeoutMs;
            return response;
        }
    }

    static class FailingClient implements AiWorkerInternalClient {
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
