package com.meeting.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqBaselineTest {

    private RabbitMQContainer rabbitmq;

    private String getBaseUrl() {
        return "http://" + rabbitmq.getHost() + ":" + rabbitmq.getHttpPort();
    }

    private String authHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
            ("guest:" + rabbitmq.getAdminPassword()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private String getJson(String path) throws Exception {
        URL url = new URL(getBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", authHeader());
        conn.setRequestProperty("Accept", "application/json");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private void postJson(String path, String body) throws Exception {
        URL url = new URL(getBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", authHeader());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        assertThat(code).isIn(200, 201, 204);
    }

    @BeforeAll
    void startAndImportDefinitions() throws Exception {
        Assumptions.assumeTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker daemon is not available — skipping Testcontainers baseline"
        );

        rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management")
        )
            .withAdminPassword("meeting_test");
        rabbitmq.start();

        // Find definitions.json from the infra package (relative to project root)
        Path definitionsPath = Paths.get(
            System.getProperty("user.dir"),
            "..", "..", "infra", "meeting-infra",
            "docker", "compose", "rabbitmq", "definitions.json"
        ).normalize();

        // Fallback: try with extra parent traversal from meeting-api-start working dir
        if (!Files.exists(definitionsPath)) {
            definitionsPath = Paths.get(
                System.getProperty("user.dir"),
                "meeting-api-infrastructure", "src", "main", "resources",
                "rabbitmq", "definitions.json"
            ).normalize();
        }

        assertThat(definitionsPath)
            .as("definitions.json must exist at " + definitionsPath)
            .exists();

        String definitionsJson = Files.readString(definitionsPath);
        postJson("/api/definitions", definitionsJson);
    }

    @AfterAll
    void cleanup() {
        if (rabbitmq != null) rabbitmq.stop();
    }

    @Test
    void rabbitMqShouldBeReachable() throws Exception {
        String overview = getJson("/api/overview");
        assertThat(overview).contains("rabbitmq");
    }

    @Test
    void amqpPortShouldBeExposed() {
        assertThat(rabbitmq.getAmqpPort()).isPositive();
    }

    @Test
    void taskExchangeShouldExist() throws Exception {
        String exchanges = getJson("/api/exchanges/%2F");
        assertThat(exchanges).contains("meeting.task.exchange");
    }

    @Test
    void deadLetterExchangeShouldExist() throws Exception {
        String exchanges = getJson("/api/exchanges/%2F");
        assertThat(exchanges).contains("meeting.task.dlx");
    }

    @Test
    void requiredQueuesShouldExist() throws Exception {
        String queues = getJson("/api/queues/%2F");
        String[] requiredQueues = {
            "audio-cpu-queue", "gpu-asr-queue", "gpu-diar-queue",
            "gpu-speaker-queue", "embed-queue", "llm-queue", "export-queue"
        };
        for (String queue : requiredQueues) {
            assertThat(queues).contains(queue);
        }
    }

    @Test
    void deadLetterQueuesShouldExist() throws Exception {
        String queues = getJson("/api/queues/%2F");
        String[] dlqQueues = {
            "audio-cpu-queue.dlq", "gpu-asr-queue.dlq", "gpu-diar-queue.dlq",
            "gpu-speaker-queue.dlq", "embed-queue.dlq", "llm-queue.dlq", "export-queue.dlq"
        };
        for (String dlq : dlqQueues) {
            assertThat(queues).contains(dlq);
        }
    }

    @Test
    void bindingsShouldRouteTaskRoutingKeys() throws Exception {
        String bindings = getJson("/api/bindings/%2F");
        assertThat(bindings).contains("task.audio-cpu");
        assertThat(bindings).contains("task.gpu-asr");
        assertThat(bindings).contains("task.embed");
        assertThat(bindings).contains("task.llm");
        assertThat(bindings).contains("task.export");
    }

    @Test
    void queuesShouldBeQuorumType() throws Exception {
        String queues = getJson("/api/queues/%2F");
        assertThat(queues).contains("x-queue-type");
    }

    @Test
    void policiesShouldExist() throws Exception {
        String policies = getJson("/api/policies/%2F");
        assertThat(policies).contains("ha-policy");
        assertThat(policies).contains("dlq-ttl-policy");
        assertThat(policies).contains("task-queue-ttl-policy");
    }
}
