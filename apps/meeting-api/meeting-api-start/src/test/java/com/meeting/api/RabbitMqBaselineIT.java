package com.meeting.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqBaselineIT {

    private static final String ADMIN_USER = "meeting";
    private static final String ADMIN_PASSWORD = "meeting_dev";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> TASK_QUEUES = Set.of(
        "audio-cpu-queue",
        "gpu-asr-queue",
        "gpu-diar-queue",
        "gpu-speaker-queue",
        "embed-queue",
        "llm-queue",
        "export-queue"
    );
    private static final Map<String, String> TASK_BINDINGS = Map.of(
        "audio-cpu-queue", "task.audio-cpu",
        "gpu-asr-queue", "task.gpu-asr",
        "gpu-diar-queue", "task.gpu-diar",
        "gpu-speaker-queue", "task.gpu-speaker",
        "embed-queue", "task.embed",
        "llm-queue", "task.llm",
        "export-queue", "task.export"
    );
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

    private JsonNode getJsonNode(String path) throws Exception {
        return OBJECT_MAPPER.readTree(getJson(path));
    }

    private Set<String> names(JsonNode array) {
        return OBJECT_MAPPER.convertValue(array.findValues("name"), new TypeReference<Set<String>>() {});
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
        JsonNode exchanges = getJsonNode("/api/exchanges/%2F");
        assertThat(names(exchanges)).contains("meeting.task.exchange", "meeting.task.dlx");

        JsonNode taskExchange = exchange(exchanges, "meeting.task.exchange");
        assertThat(taskExchange.path("type").asText()).isEqualTo("topic");
        assertThat(taskExchange.path("durable").asBoolean()).isTrue();

        JsonNode dlx = exchange(exchanges, "meeting.task.dlx");
        assertThat(dlx.path("type").asText()).isEqualTo("direct");
        assertThat(dlx.path("durable").asBoolean()).isTrue();
    }

    @Test
    void taskQueuesShouldBeLoadedFromDefinitions() throws Exception {
        JsonNode queues = getJsonNode("/api/queues/%2F");
        assertThat(names(queues)).containsAll(TASK_QUEUES);

        for (String queueName : TASK_QUEUES) {
            JsonNode queue = queue(queues, queueName);
            assertThat(queue.path("durable").asBoolean()).isTrue();
            assertThat(queue.path("arguments").path("x-queue-type").asText()).isEqualTo("quorum");
            assertThat(queue.path("arguments").path("x-dead-letter-exchange").asText()).isEqualTo("meeting.task.dlx");
            assertThat(queue.path("arguments").path("x-dead-letter-routing-key").asText()).isEqualTo(queueName + ".dlq");

            JsonNode dlq = queue(queues, queueName + ".dlq");
            assertThat(dlq.path("durable").asBoolean()).isTrue();
            assertThat(dlq.path("arguments").path("x-queue-type").asText()).isEqualTo("quorum");
        }
    }

    @Test
    void taskBindingsShouldBeLoadedFromDefinitions() throws Exception {
        JsonNode bindings = getJsonNode("/api/bindings/%2F");

        TASK_BINDINGS.forEach((queueName, routingKey) -> {
            assertThat(hasBinding(bindings, "meeting.task.exchange", queueName, routingKey)).isTrue();
            assertThat(hasBinding(bindings, "meeting.task.dlx", queueName + ".dlq", queueName + ".dlq")).isTrue();
        });
    }

    @Test
    void taskPoliciesShouldBeLoadedFromDefinitions() throws Exception {
        JsonNode policies = getJsonNode("/api/policies/%2F");
        assertThat(names(policies)).contains("dlq-ttl-policy", "task-queue-ttl-policy");

        JsonNode dlqPolicy = policy(policies, "dlq-ttl-policy");
        assertThat(dlqPolicy.path("pattern").asText()).isEqualTo(".*\\.dlq$");
        assertThat(dlqPolicy.path("definition").path("message-ttl").asLong()).isEqualTo(604800000L);
        assertThat(dlqPolicy.path("definition").path("max-length").asLong()).isEqualTo(10000L);
        assertThat(dlqPolicy.path("definition").path("overflow").asText()).isEqualTo("reject-publish");

        JsonNode taskPolicy = policy(policies, "task-queue-ttl-policy");
        assertThat(taskPolicy.path("pattern").asText()).isEqualTo("^(audio-cpu|gpu-asr|gpu-diar|gpu-speaker|embed|llm|export)-queue$");
        assertThat(taskPolicy.path("definition").path("max-length").asLong()).isEqualTo(50000L);
        assertThat(taskPolicy.path("definition").path("overflow").asText()).isEqualTo("reject-publish");
    }

    private JsonNode exchange(JsonNode exchanges, String name) {
        for (JsonNode exchange : exchanges) {
            if (name.equals(exchange.path("name").asText())) {
                return exchange;
            }
        }
        throw new AssertionError("Missing exchange: " + name);
    }

    private JsonNode queue(JsonNode queues, String name) {
        for (JsonNode queue : queues) {
            if (name.equals(queue.path("name").asText())) {
                return queue;
            }
        }
        throw new AssertionError("Missing queue: " + name);
    }

    private JsonNode policy(JsonNode policies, String name) {
        for (JsonNode policy : policies) {
            if (name.equals(policy.path("name").asText())) {
                return policy;
            }
        }
        throw new AssertionError("Missing policy: " + name);
    }

    private boolean hasBinding(JsonNode bindings, String source, String destination, String routingKey) {
        for (JsonNode binding : bindings) {
            if (
                source.equals(binding.path("source").asText()) &&
                destination.equals(binding.path("destination").asText()) &&
                routingKey.equals(binding.path("routing_key").asText()) &&
                "queue".equals(binding.path("destination_type").asText())
            ) {
                return true;
            }
        }
        return false;
    }
}
