package com.meeting.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqBaselineIT {

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

    @BeforeAll
    void startRabbitMq() {
        Assertions.assertTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker daemon is not available — Testcontainers baseline requires Docker. Run 'mvnw test' for unit tests only."
        );

        rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management")
        )
            .withAdminPassword("meeting_test");
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
}
