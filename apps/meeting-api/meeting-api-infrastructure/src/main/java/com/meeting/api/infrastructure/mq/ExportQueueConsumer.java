package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.export.ExportRenderService;
import com.meeting.api.app.export.ExportRenderService.ExportJobMessage;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bare-client RabbitMQ consumer for {@code export-queue}. Spawns a
 * dedicated connection on {@link PostConstruct} and tears it down on
 * {@link PreDestroy}; deserializes the JSON body into an
 * {@link ExportJobMessage}, sets up the tenant context indirectly via
 * {@link ExportRenderService} (which uses {@code TenantScopedTransaction})
 * and acks / nacks based on the outcome.
 *
 * <p>Retry semantics:
 * <ul>
 *   <li>Successful render → {@code basicAck}.</li>
 *   <li>{@link ExportInputInvalidException} (e.g. STALE snapshot) →
 *       {@code basicReject(requeue=false)} so the message goes to the
 *       DLQ; the service has already transitioned the job to FAILED.</li>
 *   <li>{@link ExportRuntimeException} or any other unchecked exception
 *       → {@code basicNack(requeue=true)} bounded by RabbitMQ's
 *       {@code x-death} count via the queue's TTL/max-delivery policy
 *       (configured in {@code rabbitmq/definitions.json}).</li>
 * </ul>
 *
 * <p>The consumer is opt-in via {@code meeting.export.consumer.enabled}
 * so unit / integration tests can disable it without standing up a
 * broker. Defaults to {@code true} so dev compose starts consuming on
 * boot.
 */
@Component
public class ExportQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportQueueConsumer.class);
    public static final String QUEUE_NAME = "export-queue";

    private final RabbitMqProperties properties;
    private final ObjectMapper objectMapper;
    private final ExportRenderService renderService;
    private final boolean enabled;

    private volatile Connection connection;
    private volatile Channel channel;

    public ExportQueueConsumer(
        RabbitMqProperties properties,
        ObjectMapper objectMapper,
        ExportRenderService renderService,
        @Value("${meeting.export.consumer.enabled:true}") boolean enabled
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.renderService = renderService;
        this.enabled = enabled;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("export_consumer_disabled");
            return;
        }
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(properties.host());
            factory.setPort(properties.port());
            factory.setUsername(properties.username());
            factory.setPassword(properties.password());
            factory.setVirtualHost(properties.resolvedVirtualHost());
            factory.setAutomaticRecoveryEnabled(true);

            connection = factory.newConnection("meeting-api-export-consumer");
            channel = connection.createChannel();
            channel.basicQos(1);
            channel.basicConsume(QUEUE_NAME, false, "meeting-api-export",
                new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(
                        String consumerTag, Envelope envelope,
                        com.rabbitmq.client.AMQP.BasicProperties properties, byte[] body
                    ) {
                        onMessage(envelope, body);
                    }
                });
            log.info("export_consumer_started queue={}", QUEUE_NAME);
        } catch (Exception ex) {
            // Don't fail the boot if RabbitMQ isn't reachable — log and
            // continue. The lease scanner / admin endpoint can recover
            // stuck jobs once the broker comes back. Tests run with
            // enabled=false so this branch is dev-loop-only.
            log.warn("export_consumer_start_failed reason={} (consumer disabled at runtime)",
                ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /**
     * Visible for tests — drives the same code path as the live AMQP
     * consumer, sans channel ack/nack bookkeeping.
     */
    public void onMessage(Envelope envelope, byte[] body) {
        long deliveryTag = envelope == null ? 0L : envelope.getDeliveryTag();
        ExportJobMessage msg;
        try {
            msg = deserialize(body);
        } catch (Exception ex) {
            log.warn(
                "export_consumer_bad_payload reason={} bytes={}",
                ex.getMessage(), body == null ? 0 : body.length
            );
            safeReject(deliveryTag, /* requeue */ false);
            return;
        }
        try {
            renderService.render(msg);
            safeAck(deliveryTag);
        } catch (ExportInputInvalidException ex) {
            log.info(
                "export_consumer_input_invalid tenant={} export={} code={} reason={}",
                msg.tenantId(), msg.exportId(), ex.errorCode(), ex.getMessage()
            );
            safeReject(deliveryTag, /* requeue */ false);
        } catch (ExportRuntimeException ex) {
            log.warn(
                "export_consumer_runtime_failure tenant={} export={} reason={}",
                msg.tenantId(), msg.exportId(), ex.getMessage()
            );
            safeReject(deliveryTag, /* requeue */ true);
        } catch (Exception ex) {
            log.warn(
                "export_consumer_unexpected_failure tenant={} export={} reason={}",
                msg.tenantId(), msg.exportId(), ex.getMessage()
            );
            renderService.failTerminally(msg, ErrorCode.INTERNAL_ERROR, ex.getMessage());
            safeReject(deliveryTag, /* requeue */ false);
        }
    }

    ExportJobMessage deserialize(byte[] body) throws Exception {
        var node = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
        return new ExportJobMessage(
            text(node, "tenantId"),
            text(node, "exportId"),
            text(node, "meetingId"),
            text(node, "traceId")
        );
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private void safeAck(long deliveryTag) {
        if (channel == null || deliveryTag == 0L) return;
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.warn("export_consumer_ack_failed reason={}", ex.getMessage());
        }
    }

    private void safeReject(long deliveryTag, boolean requeue) {
        if (channel == null || deliveryTag == 0L) return;
        try {
            channel.basicReject(deliveryTag, requeue);
        } catch (Exception ex) {
            log.warn("export_consumer_reject_failed reason={}", ex.getMessage());
        }
    }
}
