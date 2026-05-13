package com.meeting.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqBaselineIT {

    private static final String ADMIN_USER = "meeting";
    private static final String ADMIN_PASSWORD = "meeting_dev";
    private static final Path DEFINITIONS_PATH = Path.of(
        "..",
        "..",
        "..",
        "infra",
        "meeting-infra",
        "docker",
        "compose",
        "rabbitmq",
        "definitions.json"
    ).normalize();
    private static final Path CONFIG_PATH = Path.of(
        "..",
        "..",
        "..",
        "infra",
        "meeting-infra",
        "docker",
        "compose",
        "rabbitmq",
        "rabbitmq.conf"
    ).normalize();

    private RabbitMQContainer rabbitmq;

    private String getBaseUrl() {
        return "http://" + rabbitmq.getHost() + ":" + rabbitmq.getHttpPort();
    }

    private String authHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
            (ADMIN_USER + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8)
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

    @BeforeAll
    void startRabbitMq() {
        TestcontainersDockerPreflight.assumeDockerAvailable();
        assertThat(DEFINITIONS_PATH).exists();
        assertThat(CONFIG_PATH).exists();

        rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management")
        )
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(DEFINITIONS_PATH),
                "/etc/rabbitmq/definitions.json"
            )
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(CONFIG_PATH),
                "/etc/rabbitmq/rabbitmq.conf"
            );
        rabbitmq.start();
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
    void managementApiShouldBeAccessible() throws Exception {
        // Verify the management API returns valid JSON
        assertThat(getJson("/api/overview")).startsWith("{");
    }

    @Test
    void amqpPortShouldBeExposed() {
        assertThat(rabbitmq.getAmqpPort()).isPositive();
    }

    @Test
    void httpPortShouldBeExposed() {
        assertThat(rabbitmq.getHttpPort()).isPositive();
    }

    @Test
    void defaultVhostShouldExist() throws Exception {
        String vhosts = getJson("/api/vhosts");
        assertThat(vhosts).contains("/");
    }

    @Test
    void taskExchangesShouldBeLoadedFromDefinitions() throws Exception {
        String exchanges = getJson("/api/exchanges/%2F");
        assertThat(exchanges)
            .contains("meeting.task.exchange")
            .contains("meeting.task.dlx");
    }

    @Test
    void taskQueuesShouldBeLoadedFromDefinitions() throws Exception {
        String queues = getJson("/api/queues/%2F");
        assertThat(queues)
            .contains("audio-cpu-queue")
            .contains("gpu-asr-queue")
            .contains("gpu-diar-queue")
            .contains("gpu-speaker-queue")
            .contains("embed-queue")
            .contains("llm-queue")
            .contains("export-queue");
    }

    @Test
    void taskBindingsShouldBeLoadedFromDefinitions() throws Exception {
        String bindings = getJson("/api/bindings/%2F");
        assertThat(bindings)
            .contains("meeting.task.exchange")
            .contains("task.audio-cpu")
            .contains("task.gpu-asr")
            .contains("task.gpu-diar")
            .contains("task.gpu-speaker")
            .contains("task.embed")
            .contains("task.llm")
            .contains("task.export")
            .contains("meeting.task.dlx")
            .contains("audio-cpu-queue.dlq")
            .contains("gpu-asr-queue.dlq")
            .contains("gpu-diar-queue.dlq")
            .contains("gpu-speaker-queue.dlq")
            .contains("embed-queue.dlq")
            .contains("llm-queue.dlq")
            .contains("export-queue.dlq");
    }

    @Test
    void taskPoliciesShouldBeLoadedFromDefinitions() throws Exception {
        String policies = getJson("/api/policies/%2F");
        assertThat(policies)
            .contains("dlq-ttl-policy")
            .contains("task-queue-ttl-policy");
    }
}
