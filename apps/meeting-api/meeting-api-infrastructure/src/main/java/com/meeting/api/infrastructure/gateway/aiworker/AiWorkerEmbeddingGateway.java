package com.meeting.api.infrastructure.gateway.aiworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.domain.rag.EmbeddingGateway;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmbeddingGateway} backed by ai-worker's
 * {@code POST /internal/embed} endpoint. Wraps every call with the
 * {@code aiworker.calls} Micrometer counter so dashboards can split
 * success / unavailable / contract_error.
 */
@Component
public class AiWorkerEmbeddingGateway implements EmbeddingGateway {

    private static final String OPERATION = "embed";
    private static final String DECLARED_MODEL_VERSION = "bge-m3-v1";

    private final AiWorkerInternalClient client;
    private final AiWorkerInternalProperties properties;
    private final ObjectMapper objectMapper;
    private final MeetingApiMetrics metrics;

    public AiWorkerEmbeddingGateway(
        AiWorkerInternalClient client,
        AiWorkerInternalProperties properties,
        ObjectMapper objectMapper,
        MeetingApiMetrics metrics
    ) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public EmbedResult embed(EmbedRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("tenantId", request.tenantId());
        body.put("modelVersion", DECLARED_MODEL_VERSION);
        ArrayNode texts = body.putArray("texts");
        for (String t : request.texts()) {
            texts.add(t);
        }

        metrics.aiWorkerCallCounter(OPERATION, "called").increment();
        try {
            JsonNode data = client.call(
                "POST",
                "/embed",
                body,
                request.tenantId(),
                request.requestId(),
                request.traceId(),
                properties.embedTimeoutMs()
            );
            EmbedResult result = parse(data);
            metrics.aiWorkerCallCounter(OPERATION, "success").increment();
            return result;
        } catch (AiWorkerUnavailableException e) {
            metrics.aiWorkerCallCounter(OPERATION, "unavailable").increment();
            throw e;
        } catch (AiWorkerContractException e) {
            metrics.aiWorkerCallCounter(OPERATION, "contract_error").increment();
            throw e;
        }
    }

    private EmbedResult parse(JsonNode data) {
        String modelVersion = data.path("modelVersion").asText("");
        int dimension = data.path("dimension").asInt(0);
        JsonNode vectorsNode = data.get("vectors");
        if (vectorsNode == null || !vectorsNode.isArray()) {
            throw new AiWorkerContractException(
                "AI_WORKER_INVALID_ENVELOPE",
                "embed response missing 'vectors' array"
            );
        }
        if (dimension <= 0) {
            throw new AiWorkerContractException(
                "AI_WORKER_INVALID_ENVELOPE",
                "embed response missing or non-positive 'dimension'"
            );
        }
        List<float[]> vectors = new ArrayList<>(vectorsNode.size());
        for (JsonNode vec : vectorsNode) {
            if (!vec.isArray() || vec.size() != dimension) {
                throw new AiWorkerContractException(
                    "AI_WORKER_INVALID_ENVELOPE",
                    "embed response vector length " + (vec.isArray() ? vec.size() : "not-array")
                        + " does not match declared dimension " + dimension
                );
            }
            float[] floats = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                floats[i] = (float) vec.get(i).asDouble();
            }
            vectors.add(floats);
        }
        return new EmbedResult(modelVersion, dimension, vectors);
    }
}
