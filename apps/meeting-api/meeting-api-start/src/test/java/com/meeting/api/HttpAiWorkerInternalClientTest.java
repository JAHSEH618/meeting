package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalProperties;
import com.meeting.api.infrastructure.gateway.aiworker.HttpAiWorkerInternalClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAiWorkerInternalClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_SECRET = "test-secret-abc";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsCanonicalHmacHeadersAndParsesData() {
        AtomicReference<HttpExchange> seen = new AtomicReference<>();
        server.createContext("/internal/embed", exchange -> {
            seen.set(exchange);
            respond(exchange, 200, envelope(true, MAPPER.createObjectNode().put("ok", true), null));
        });

        HttpAiWorkerInternalClient client = newClient();
        ObjectNode body = MAPPER.createObjectNode().put("tenantId", "t").put("modelVersion", "v");

        JsonNode data = client.call("POST", "/embed", body, "tenant_01", "req_x", "trace_x", 2000);

        assertThat(data.path("ok").asBoolean()).isTrue();

        HttpExchange ex = seen.get();
        assertThat(ex.getRequestHeaders().getFirst("X-Request-Id")).isEqualTo("req_x");
        assertThat(ex.getRequestHeaders().getFirst("X-Trace-Id")).isEqualTo("trace_x");
        assertThat(ex.getRequestHeaders().getFirst("X-Tenant-Id")).isEqualTo("tenant_01");
        String timestamp = ex.getRequestHeaders().getFirst("X-Timestamp");
        assertThat(timestamp).isEqualTo("2026-05-15T10:00:00Z");
        String nonce = ex.getRequestHeaders().getFirst("X-Nonce");
        assertThat(nonce).startsWith("client-");
        String signature = ex.getRequestHeaders().getFirst("X-Signature");
        assertThat(signature).startsWith("hmac-sha256=");
        assertThat(signature).isEqualTo(expectedSignature("POST", "/internal/embed", body, timestamp, nonce));
    }

    @Test
    void getRequestSignsWithEmptyBodyHash() {
        AtomicReference<HttpExchange> seen = new AtomicReference<>();
        server.createContext("/internal/models", exchange -> {
            seen.set(exchange);
            respond(exchange, 200, envelope(true, MAPPER.createObjectNode().putArray("models"), null));
        });

        HttpAiWorkerInternalClient client = newClient();
        client.call("GET", "/models", null, "tenant_01", "req_m", "trace_m", 2000);

        HttpExchange ex = seen.get();
        String timestamp = ex.getRequestHeaders().getFirst("X-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Nonce");
        String expected = expectedSignature("GET", "/internal/models", null, timestamp, nonce);
        assertThat(ex.getRequestHeaders().getFirst("X-Signature")).isEqualTo(expected);
    }

    @Test
    void status503MapsToUnavailable() {
        server.createContext("/internal/rerank", exchange -> respond(
            exchange,
            503,
            envelope(false, null, MAPPER.createObjectNode().put("code", "RERANK_UNAVAILABLE").put("message", "model down").put("retryable", true))
        ));

        HttpAiWorkerInternalClient client = newClient();

        assertThatThrownBy(() -> client.call("POST", "/rerank", MAPPER.createObjectNode(), "t", "r", "tr", 2000))
            .isInstanceOf(AiWorkerUnavailableException.class)
            .hasMessageContaining("model down");
    }

    @Test
    void status400MapsToContractException() {
        server.createContext("/internal/embed", exchange -> respond(
            exchange,
            400,
            envelope(false, null, MAPPER.createObjectNode().put("code", "EMBEDDING_CONTRACT_ERROR").put("message", "bad shape").put("retryable", false))
        ));

        HttpAiWorkerInternalClient client = newClient();

        assertThatThrownBy(() -> client.call("POST", "/embed", MAPPER.createObjectNode(), "t", "r", "tr", 2000))
            .isInstanceOf(AiWorkerContractException.class)
            .hasMessageContaining("bad shape");
    }

    @Test
    void status401MapsToContractException() {
        server.createContext("/internal/embed", exchange -> respond(
            exchange,
            401,
            envelope(false, null, MAPPER.createObjectNode().put("code", "EMBEDDING_AUTH_FAILED").put("message", "hmac").put("retryable", false))
        ));

        HttpAiWorkerInternalClient client = newClient();

        assertThatThrownBy(() -> client.call("POST", "/embed", MAPPER.createObjectNode(), "t", "r", "tr", 2000))
            .isInstanceOf(AiWorkerContractException.class);
    }

    @Test
    void status500MapsToUnavailableLikeOther5xx() {
        server.createContext("/internal/rerank", exchange -> respond(
            exchange,
            500,
            envelope(false, null, MAPPER.createObjectNode().put("code", "INTERNAL_ERROR").put("message", "boom").put("retryable", true))
        ));

        HttpAiWorkerInternalClient client = newClient();

        assertThatThrownBy(() -> client.call("POST", "/rerank", MAPPER.createObjectNode(), "t", "r", "tr", 2000))
            .isInstanceOf(AiWorkerUnavailableException.class);
    }

    @Test
    void unparsableBodyIsContractException() {
        server.createContext("/internal/embed", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = "not-json".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException io) {
                throw new RuntimeException(io);
            } finally {
                exchange.close();
            }
        });

        HttpAiWorkerInternalClient client = newClient();

        assertThatThrownBy(() -> client.call("POST", "/embed", MAPPER.createObjectNode(), "t", "r", "tr", 2000))
            .isInstanceOf(AiWorkerContractException.class)
            .hasMessageContaining("unparsable");
    }

    @Test
    void timeoutMapsToUnavailable() {
        server.createContext("/internal/rerank", exchange -> {
            try {
                Thread.sleep(2000); // longer than client timeout
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, envelope(true, MAPPER.createObjectNode(), null));
        });

        HttpAiWorkerInternalClient client = newClient();

        assertThatThrownBy(() -> client.call("POST", "/rerank", MAPPER.createObjectNode(), "t", "r", "tr", 200))
            .isInstanceOf(AiWorkerUnavailableException.class);
    }

    @Test
    void connectionRefusedMapsToUnavailable() {
        // Stop the server first so the next request gets connection refused.
        server.stop(0);
        server = null;

        HttpAiWorkerInternalClient client = newClient();

        assertThatThrownBy(() -> client.call("POST", "/rerank", MAPPER.createObjectNode(), "t", "r", "tr", 1000))
            .isInstanceOf(AiWorkerUnavailableException.class);
    }

    private HttpAiWorkerInternalClient newClient() {
        AiWorkerInternalProperties props = new AiWorkerInternalProperties(
            "http://127.0.0.1:" + port, HMAC_SECRET, 3000, 3000, 5000, 2000, 1000
        );
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
            .build();
        return new HttpAiWorkerInternalClient(props, MAPPER, FIXED, http);
    }

    private static String expectedSignature(String method, String fullPath, JsonNode body, String timestamp, String nonce) {
        try {
            byte[] bytes;
            if (body == null) {
                bytes = new byte[0];
            } else {
                bytes = MAPPER.writeValueAsBytes(body);
            }
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String signingString = timestamp + "\n" + nonce + "\n" + method + "\n" + fullPath + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "hmac-sha256=" + HexFormat.of().formatHex(mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String envelope(boolean success, JsonNode data, JsonNode error) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("success", success);
        if (data == null) {
            env.putNull("data");
        } else {
            env.set("data", data);
        }
        if (error == null) {
            env.putNull("error");
        } else {
            env.set("error", error);
        }
        env.put("requestId", "req_x");
        env.put("traceId", "trace_x");
        return env.toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException io) {
            throw new RuntimeException(io);
        } finally {
            exchange.close();
        }
    }
}
