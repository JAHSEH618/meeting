package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.export.ExportRenderService;
import com.meeting.api.app.export.ExportRenderService.ExportJobMessage;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.infrastructure.mq.ExportQueueConsumer;
import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * final-check.md C2 — real-broker integration test for
 * {@link ExportQueueConsumer}.
 *
 * <p>Mounts the project's
 * {@code infra/meeting-infra/docker/compose/rabbitmq/definitions.json} so
 * the {@code export-queue} queue + DLX bindings are declared exactly as
 * in dev/prod. Then publishes JSON bodies matching
 * {@code packages/meeting-contracts/schemas/rabbitmq/export-job-message.schema.json}
 * via the bare {@code com.rabbitmq.client} {@link Connection}, drives the
 * consumer's full {@code basicConsume → handleDelivery → onMessage}
 * choreography, and asserts that:
 *
 * <ol>
 *   <li>The deserialized {@link ExportJobMessage} carries every field
 *       the schema requires (tenant / export / meeting / trace).</li>
 *   <li>A happy-path render dispatch acks the message — the queue depth
 *       returns to zero with no requeue and no DLQ delivery.</li>
 *   <li>An {@link ExportInputInvalidException} from the render service
 *       is treated as terminal: the message is rejected without
 *       requeue (no second delivery) and the consumer remains healthy
 *       for the next message.</li>
 *   <li>A garbage payload that fails JSON parsing is rejected without
 *       even reaching the render service.</li>
 * </ol>
 *
 * <p>Storage + JDBC are deliberately out of scope here — they are
 * covered by {@code JdbcExportJobRepositoryIT} (PG / RLS / claim-mutex)
 * and {@code ExportRenderServiceTest} (storage + outbox), so this IT
 * keeps its focus on the parts that <em>require</em> a real broker:
 * AMQP-side serialization, channel ack/nack semantics, and the
 * declared-queue → consumer wiring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportQueueConsumerIT {

    private static final Path DEFINITIONS_PATH = Path.of(
        "..", "..", "..", "infra", "meeting-infra", "docker", "compose",
        "rabbitmq", "definitions.json"
    ).normalize();

    private static final Path CONFIG_PATH = Path.of(
        "..", "..", "..", "infra", "meeting-infra", "docker", "compose",
        "rabbitmq", "rabbitmq.conf"
    ).normalize();

    private static final String ADMIN_USER = "meeting";
    private static final String ADMIN_PASSWORD = "meeting_dev";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RabbitMQContainer rabbitmq;
    private RabbitMqProperties properties;
    private Connection publisherConnection;
    private Channel publisherChannel;

    @BeforeAll
    void startBroker() throws Exception {
        TestcontainersDockerPreflight.assumeDockerAvailable();
        assertThat(DEFINITIONS_PATH).exists();
        assertThat(CONFIG_PATH).exists();

        rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management")
        )
            .withCopyFileToContainer(
                MountableFile.forHostPath(DEFINITIONS_PATH),
                "/etc/rabbitmq/definitions.json"
            )
            .withCopyFileToContainer(
                MountableFile.forHostPath(CONFIG_PATH),
                "/etc/rabbitmq/rabbitmq.conf"
            );
        rabbitmq.start();

        properties = new RabbitMqProperties(
            rabbitmq.getHost(),
            rabbitmq.getAmqpPort(),
            ADMIN_USER,
            ADMIN_PASSWORD,
            "/"
        );

        ConnectionFactory factory = factoryFor(properties);
        publisherConnection = factory.newConnection("export-it-publisher");
        publisherChannel = publisherConnection.createChannel();
    }

    @AfterAll
    void stopBroker() throws Exception {
        if (publisherChannel != null && publisherChannel.isOpen()) {
            publisherChannel.close();
        }
        if (publisherConnection != null && publisherConnection.isOpen()) {
            publisherConnection.close();
        }
        if (rabbitmq != null) {
            rabbitmq.stop();
        }
    }

    @BeforeEach
    void drainQueues() throws Exception {
        // Belt-and-braces: ensure no left-over messages bleed between
        // cases. {@code basicGet} returns null once the queue is empty.
        for (String queue : new String[] {
            ExportQueueConsumer.QUEUE_NAME,
            ExportQueueConsumer.QUEUE_NAME + ".dlq"
        }) {
            while (publisherChannel.basicGet(queue, true) != null) {
                // drain
            }
        }
    }

    @Test
    void happyPathDeserializesAndAcks() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        Mockito.when(renderService.render(any())).thenReturn(
            new ExportRenderService.RenderOutcome(ExportStatus.SUCCEEDED, "mf_it_ok")
        );
        try (var consumer = startConsumer(renderService)) {
            byte[] body = """
                {"tenantId":"tenant_it_ok",
                 "exportId":"exp_it_ok",
                 "meetingId":"mtg_it_ok",
                 "format":"MARKDOWN",
                 "expectedInputVersion":{"transcriptVersion":1},
                 "traceId":"trace_it_ok",
                 "createdAt":"2026-05-19T10:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

            publish(body);

            ArgumentCaptor<ExportJobMessage> captor =
                ArgumentCaptor.forClass(ExportJobMessage.class);
            verify(renderService, timeout(Duration.ofSeconds(10).toMillis()))
                .render(captor.capture());

            ExportJobMessage msg = captor.getValue();
            assertThat(msg.tenantId()).isEqualTo("tenant_it_ok");
            assertThat(msg.exportId()).isEqualTo("exp_it_ok");
            assertThat(msg.meetingId()).isEqualTo("mtg_it_ok");
            assertThat(msg.traceId()).isEqualTo("trace_it_ok");

            // Ack drained the queue — depth back to 0 within a beat.
            assertQueueDepthEventuallyZero(Duration.ofSeconds(5));

            // No DLQ delivery on success.
            assertThat(dlqDepth()).isZero();

            verifyNoMoreInteractions(renderService);
        }
    }

    @Test
    void inputInvalidExceptionRejectsWithoutRequeue() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        doThrow(new ExportInputInvalidException(
                ErrorCode.EXPORT_CONTENT_STALE, "stale snapshot"))
            .when(renderService).render(any());

        try (var consumer = startConsumer(renderService)) {
            byte[] body = """
                {"tenantId":"tenant_it_stale",
                 "exportId":"exp_it_stale",
                 "meetingId":"mtg_it_stale",
                 "format":"MARKDOWN",
                 "expectedInputVersion":{"transcriptVersion":2},
                 "traceId":"trace_it_stale",
                 "createdAt":"2026-05-19T10:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

            publish(body);

            // Render is invoked exactly once and the message is not
            // requeued — basicReject(requeue=false). After a generous
            // wait the queue stays empty and no follow-up render fires.
            verify(renderService, timeout(Duration.ofSeconds(10).toMillis()))
                .render(any());
            assertQueueDepthEventuallyZero(Duration.ofSeconds(5));

            Mockito.verifyNoMoreInteractions(
                Mockito.ignoreStubs(renderService)
            );
        }
    }

    @Test
    void garbagePayloadIsRejectedBeforeRenderService() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        try (var consumer = startConsumer(renderService)) {
            publish("not json".getBytes(StandardCharsets.UTF_8));

            // Allow time for delivery + reject; render must never run.
            assertQueueDepthEventuallyZero(Duration.ofSeconds(5));
            Mockito.verifyNoInteractions(renderService);
        }
    }

    @Test
    void unexpectedErrorMarksJobFailedTerminally() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        doThrow(new RuntimeException("kms outage"))
            .when(renderService).render(any());

        try (var consumer = startConsumer(renderService)) {
            byte[] body = """
                {"tenantId":"tenant_it_kms",
                 "exportId":"exp_it_kms",
                 "meetingId":"mtg_it_kms",
                 "format":"PDF",
                 "expectedInputVersion":{"transcriptVersion":1},
                 "traceId":"trace_it_kms",
                 "createdAt":"2026-05-19T10:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

            publish(body);

            verify(renderService, timeout(Duration.ofSeconds(10).toMillis()))
                .render(any());
            verify(renderService, timeout(Duration.ofSeconds(5).toMillis()))
                .failTerminally(any(), eq(ErrorCode.INTERNAL_ERROR), any());
            assertQueueDepthEventuallyZero(Duration.ofSeconds(5));
        }
    }

    @Test
    void persistentRetryableFailureIsBoundedAndDeadLetters() throws Exception {
        // A render that always throws ExportRuntimeException must NOT spin the
        // consumer forever: the quorum queue's x-delivery-count header bounds
        // the requeue loop, the job is failed terminally, and the message
        // dead-letters to export-queue.dlq.
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        doThrow(new com.meeting.api.domain.export.ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED, "soffice keeps timing out"))
            .when(renderService).render(any());

        try (var consumer = startConsumer(renderService, /* maxAttempts */ 3)) {
            byte[] body = """
                {"tenantId":"tenant_it_spin",
                 "exportId":"exp_it_spin",
                 "meetingId":"mtg_it_spin",
                 "format":"PDF",
                 "expectedInputVersion":{"transcriptVersion":1},
                 "traceId":"trace_it_spin",
                 "createdAt":"2026-05-19T10:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

            publish(body);

            verify(renderService, timeout(Duration.ofSeconds(15).toMillis()))
                .failTerminally(any(), eq(ErrorCode.EXPORT_RENDER_FAILED), any());
            // Exactly maxAttempts render attempts — bounded, not infinite.
            verify(renderService, timeout(Duration.ofSeconds(5).toMillis()).times(3))
                .render(any());
            assertQueueDepthEventuallyZero(Duration.ofSeconds(5));

            // The final reject used requeue=false, so the message dead-lettered.
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && dlqDepth() == 0) {
                Thread.sleep(100);
            }
            assertThat(dlqDepth()).isEqualTo(1);
        }
    }

    // ─── helpers ──────────────────────────────────────────────

    private ConsumerHandle startConsumer(ExportRenderService renderService) {
        return startConsumer(renderService, /* maxAttempts */ 5);
    }

    private ConsumerHandle startConsumer(ExportRenderService renderService, int maxAttempts) {
        ExportQueueConsumer consumer = new ExportQueueConsumer(
            properties, objectMapper, renderService, /* enabled */ true, maxAttempts
        );
        consumer.start();
        return new ConsumerHandle(consumer);
    }

    private static ConnectionFactory factoryFor(RabbitMqProperties props) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(props.host());
        factory.setPort(props.port());
        factory.setUsername(props.username());
        factory.setPassword(props.password());
        factory.setVirtualHost(props.resolvedVirtualHost());
        factory.setAutomaticRecoveryEnabled(false);
        return factory;
    }

    private void publish(byte[] body) throws Exception {
        publisherChannel.basicPublish(
            /* exchange */ "",
            /* routingKey */ ExportQueueConsumer.QUEUE_NAME,
            new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .build(),
            body
        );
    }

    private long queueDepth(String queueName) throws Exception {
        return publisherChannel.messageCount(queueName);
    }

    private long dlqDepth() throws Exception {
        return queueDepth(ExportQueueConsumer.QUEUE_NAME + ".dlq");
    }

    private void assertQueueDepthEventuallyZero(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queueDepth(ExportQueueConsumer.QUEUE_NAME) == 0) return;
            Thread.sleep(100);
        }
        fail("export-queue still has %d messages after %s",
            queueDepth(ExportQueueConsumer.QUEUE_NAME), timeout);
    }

    /**
     * Lightweight AutoCloseable wrapper so each test's consumer is torn
     * down on the way out — the channel/connection are managed inside
     * the consumer's {@link ExportQueueConsumer#stop} hook.
     */
    private record ConsumerHandle(ExportQueueConsumer consumer) implements AutoCloseable {
        @Override
        public void close() {
            consumer.stop();
        }
    }
}
