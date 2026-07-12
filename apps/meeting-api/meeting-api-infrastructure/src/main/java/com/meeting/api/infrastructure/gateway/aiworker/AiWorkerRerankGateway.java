package com.meeting.api.infrastructure.gateway.aiworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.domain.rag.RerankGateway;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Default {@link RerankGateway} backed by ai-worker's
 * {@code POST /internal/rerank} endpoint. The timeout is tight by
 * design — rerank sits on the user-visible RAG query critical path and
 * the app layer falls back to RRF ordering on timeout (counted under
 * {@code aiworker.calls{operation=rerank,outcome=unavailable}}).
 *
 * <p>A circuit breaker guards the call: after
 * {@code rerank-breaker-failure-threshold} consecutive unavailable/timeout
 * outcomes the circuit opens for {@code rerank-breaker-open-ms} and calls
 * short-circuit to {@link AiWorkerUnavailableException} without paying the
 * timeout — the app layer's RRF fallback then answers immediately instead
 * of stalling every RAG query behind a dead reranker. Contract errors mean
 * ai-worker responded, so they count as availability successes.
 */
@Component
public class AiWorkerRerankGateway implements RerankGateway {

    private static final String OPERATION = "rerank";

    private final AiWorkerInternalClient client;
    private final AiWorkerInternalProperties properties;
    private final ObjectMapper objectMapper;
    private final MeetingApiMetrics metrics;
    private final AiWorkerCircuitBreaker breaker;

    public AiWorkerRerankGateway(
        AiWorkerInternalClient client,
        AiWorkerInternalProperties properties,
        ObjectMapper objectMapper,
        MeetingApiMetrics metrics
    ) {
        this(client, properties, objectMapper, metrics, new AiWorkerCircuitBreaker(
            properties.rerankBreakerFailureThreshold(),
            Duration.ofMillis(properties.rerankBreakerOpenMs()),
            System::nanoTime
        ));
    }

    public AiWorkerRerankGateway(
        AiWorkerInternalClient client,
        AiWorkerInternalProperties properties,
        ObjectMapper objectMapper,
        MeetingApiMetrics metrics,
        AiWorkerCircuitBreaker breaker
    ) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.breaker = breaker;
    }

    @Override
    public RerankResult rerank(RerankRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("tenantId", request.tenantId());
        body.put("query", request.query());
        body.put("topN", request.topN());
        body.put("modelVersion", request.modelVersion());
        ArrayNode candidates = body.putArray("candidates");
        for (RerankCandidate c : request.candidates()) {
            ObjectNode item = candidates.addObject();
            item.put("chunkId", c.chunkId());
            item.put("sourceType", c.sourceType());
            item.put("text", c.text());
            item.put("rrfScore", c.rrfScore());
            if (c.sourceVersion() != null) {
                item.put("sourceVersion", c.sourceVersion());
            }
        }

        metrics.aiWorkerCallCounter(OPERATION, "called").increment();
        if (!breaker.tryAcquire()) {
            metrics.aiWorkerCallCounter(OPERATION, "circuit_open").increment();
            throw new AiWorkerUnavailableException(
                "RERANK_CIRCUIT_OPEN",
                "rerank circuit breaker open; skipping call"
            );
        }
        try {
            JsonNode data = client.call(
                "POST",
                "/rerank",
                body,
                request.tenantId(),
                request.requestId(),
                request.traceId(),
                properties.rerankTimeoutMs()
            );
            RerankResult result = parse(data);
            breaker.recordSuccess();
            metrics.aiWorkerCallCounter(OPERATION, "success").increment();
            return result;
        } catch (AiWorkerUnavailableException e) {
            breaker.recordFailure();
            metrics.aiWorkerCallCounter(OPERATION, "unavailable").increment();
            throw e;
        } catch (AiWorkerContractException e) {
            // ai-worker responded — the service is up, so this is not an
            // availability failure for breaker purposes.
            breaker.recordSuccess();
            metrics.aiWorkerCallCounter(OPERATION, "contract_error").increment();
            throw e;
        }
    }

    private RerankResult parse(JsonNode data) {
        String modelVersion = data.path("modelVersion").asText("");
        JsonNode itemsNode = data.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            throw new AiWorkerContractException(
                "AI_WORKER_INVALID_ENVELOPE",
                "rerank response missing 'items' array"
            );
        }
        List<RankedItem> items = new ArrayList<>(itemsNode.size());
        for (JsonNode item : itemsNode) {
            String chunkId = item.path("chunkId").asText(null);
            if (chunkId == null || chunkId.isBlank()) {
                throw new AiWorkerContractException(
                    "AI_WORKER_INVALID_ENVELOPE",
                    "rerank item missing chunkId"
                );
            }
            int rank = item.path("rank").asInt(0);
            double score = item.path("rerankScore").asDouble(Double.NaN);
            if (rank < 1 || Double.isNaN(score)) {
                throw new AiWorkerContractException(
                    "AI_WORKER_INVALID_ENVELOPE",
                    "rerank item " + chunkId + " has invalid rank=" + rank + " score=" + score
                );
            }
            items.add(new RankedItem(chunkId, rank, score));
        }
        return new RerankResult(modelVersion, items);
    }
}
