package com.meeting.api;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RabbitMQ baseline test.
 * Verifies:
 * 1. RabbitMQ container starts and management API is reachable.
 * 2. Default user can authenticate.
 */
@Testcontainers
class RabbitMqBaselineTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(
        DockerImageName.parse("rabbitmq:3.13-management")
    )
        .withAdminPassword("meeting_test");

    @Test
    void rabbitMqShouldBeReachable() throws Exception {
        String mgmtUrl = "http://" + rabbitmq.getHost() + ":" + rabbitmq.getHttpPort() + "/api/overview";
        URL url = new URL(mgmtUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        String auth = Base64.getEncoder().encodeToString(
            ("guest:" + rabbitmq.getAdminPassword()).getBytes(StandardCharsets.UTF_8)
        );
        conn.setRequestProperty("Authorization", "Basic " + auth);

        int responseCode = conn.getResponseCode();
        assertThat(responseCode).isEqualTo(200);
    }

    @Test
    void amqpPortShouldBeExposed() {
        assertThat(rabbitmq.getAmqpPort()).isPositive();
    }
}
