package com.meeting.api.infrastructure.gateway.aiworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Production {@link AiWorkerInternalClient} backed by Java's built-in
 * {@link HttpClient}. Constructs the canonical signing string defined in
 * {@code ai-worker-internal-api.yaml}:
 *
 * <pre>
 *   timestamp + "\n" + nonce + "\n" + METHOD + "\n" +
 *   path-with-query + "\n" + sha256_hex(body)
 * </pre>
 *
 * <p>Note: {@code path-with-query} MUST include the leading {@code /internal}
 * prefix (see CLAUDE.md HMAC note). All ai-worker endpoints live under
 * {@code /internal/...} so we hard-code the prefix when building the URI.
 */
@Component
public class HttpAiWorkerInternalClient implements AiWorkerInternalClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiWorkerInternalClient.class);
    private static final DateTimeFormatter ISO_INSTANT_SECONDS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String INTERNAL_PREFIX = "/internal";

    private final AiWorkerInternalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;

    @Autowired
    public HttpAiWorkerInternalClient(
        AiWorkerInternalProperties properties,
        ObjectMapper objectMapper
    ) {
        this(properties, objectMapper, Clock.systemUTC());
    }
    public HttpAiWorkerInternalClient(
        AiWorkerInternalProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this(properties, objectMapper, clock, HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
            .build());
    }

    /** Visible for tests so a mock {@link HttpClient} can be injected. */
    public HttpAiWorkerInternalClient(
        AiWorkerInternalProperties properties,
        ObjectMapper objectMapper,
        Clock clock,
        HttpClient httpClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = httpClient;
    }

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
        String bodyJson;
        try {
            bodyJson = body == null ? "" : objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new AiWorkerContractException(
                "AI_WORKER_REQUEST_SERIALIZATION_FAILED",
                "could not serialize request body: " + e.getMessage(),
                e
            );
        }
        byte[] bodyBytes = bodyJson.getBytes(StandardCharsets.UTF_8);

        String fullPath = INTERNAL_PREFIX + path;
        String timestamp = ISO_INSTANT_SECONDS.format(OffsetDateTime.now(clock));
        String nonce = "client-" + UUID.randomUUID();
        String bodyHash = sha256Hex(bodyBytes);
        String signingString = timestamp + "\n" + nonce + "\n" + method + "\n" + fullPath + "\n" + bodyHash;
        String signature = "hmac-sha256=" + hmacSha256Hex(signingString, properties.hmacSecret());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(properties.baseUrl() + fullPath))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .header("X-Request-Id", requestId)
            .header("X-Trace-Id", traceId)
            .header("X-Tenant-Id", tenantId)
            .header("X-Timestamp", timestamp)
            .header("X-Nonce", nonce)
            .header("X-Signature", signature);

        if (body == null) {
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new AiWorkerUnavailableException(
                "AI_WORKER_TIMEOUT",
                "ai-worker " + method + " " + fullPath + " timed out after " + timeoutMs + "ms",
                timeout
            );
        } catch (java.io.IOException io) {
            throw new AiWorkerUnavailableException(
                "AI_WORKER_TRANSPORT_ERROR",
                "ai-worker " + method + " " + fullPath + " failed: " + io.getMessage(),
                io
            );
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiWorkerUnavailableException(
                "AI_WORKER_INTERRUPTED",
                "interrupted while calling ai-worker",
                ie
            );
        }

        return parseEnvelope(method, fullPath, response);
    }

    private JsonNode parseEnvelope(String method, String fullPath, HttpResponse<String> response) {
        int status = response.statusCode();
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(response.body() == null ? "{}" : response.body());
        } catch (Exception e) {
            throw new AiWorkerContractException(
                "AI_WORKER_INVALID_ENVELOPE",
                "ai-worker " + method + " " + fullPath + " returned unparsable body (status=" + status + "): " + e.getMessage(),
                e
            );
        }

        if (status == 200) {
            // Special exemption: health, ready, and hardware endpoints return unwrapped flat JSON
            if (fullPath.endsWith("/health") || fullPath.endsWith("/ready") || fullPath.endsWith("/hardware")) {
                return envelope;
            }

            JsonNode data = envelope.get("data");
            if (data == null || data.isNull()) {
                throw new AiWorkerContractException(
                    "AI_WORKER_INVALID_ENVELOPE",
                    "ai-worker " + method + " " + fullPath + " 200 response has null data"
                );
            }
            return data;
        }

        JsonNode error = envelope.get("error");
        String code = error == null ? "AI_WORKER_UNKNOWN_ERROR" : error.path("code").asText("AI_WORKER_UNKNOWN_ERROR");
        String message = error == null
            ? "ai-worker returned " + status + " with no error envelope"
            : error.path("message").asText("ai-worker returned " + status);

        if (status == 503) {
            throw new AiWorkerUnavailableException(code, message);
        }
        if (status == 400 || status == 401 || status == 422) {
            throw new AiWorkerContractException(code, message);
        }
        if (status >= 500) {
            // Treat 500/502/504 the same as 503 — transient upstream failures.
            throw new AiWorkerUnavailableException(code, message);
        }
        throw new AiWorkerContractException(code, message);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256Hex(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
