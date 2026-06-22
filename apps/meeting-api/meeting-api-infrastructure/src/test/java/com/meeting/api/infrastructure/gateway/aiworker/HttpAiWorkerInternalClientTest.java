package com.meeting.api.infrastructure.gateway.aiworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpAiWorkerInternalClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
    void trailingSlashBaseUrlStillRequestsCanonicalInternalPath() {
        AtomicReference<String> seenPath = new AtomicReference<>();
        server.createContext("/internal/embed", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, envelope(true, MAPPER.createObjectNode().put("ok", true), null));
        });
        HttpAiWorkerInternalClient client = newClient("http://127.0.0.1:" + port + "/");

        JsonNode data = client.call(
            "POST",
            "/embed",
            MAPPER.createObjectNode().put("tenantId", "tenant_01"),
            "tenant_01",
            "req_01",
            "trace_01",
            2000
        );

        assertThat(data.path("ok").asBoolean()).isTrue();
        assertThat(seenPath).hasValue("/internal/embed");
    }

    private HttpAiWorkerInternalClient newClient(String baseUrl) {
        AiWorkerInternalProperties props = new AiWorkerInternalProperties(
            baseUrl,
            "test-secret-abc",
            3000,
            3000,
            5000,
            2000,
            1000
        );
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
            .build();
        return new HttpAiWorkerInternalClient(props, MAPPER, FIXED, http);
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
        env.put("requestId", "req_01");
        env.put("traceId", "trace_01");
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
