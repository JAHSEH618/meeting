package com.meeting.api.infrastructure.mq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RabbitMqPublisher {
    public static final String TASK_EXCHANGE = "meeting.task.exchange";

    private static final Logger log = LoggerFactory.getLogger(RabbitMqPublisher.class);

    /** How long to wait for a broker confirm before treating the publish as failed. */
    private static final long CONFIRM_TIMEOUT_MS = 5_000L;

    private final RabbitMqProperties properties;

    /**
     * Long-lived, shared AMQP connection. The connection handshake
     * (TCP + AMQP + SASL auth) is expensive, so we open it once and
     * reuse it across publishes, creating a cheap, short-lived
     * {@link Channel} per message. Guarded by {@code this} via
     * {@link #getOrCreateConnection()} and recreated if it has been
     * closed (e.g. after a broker restart).
     */
    private volatile Connection connection;

    public RabbitMqPublisher(RabbitMqProperties properties) {
        this.properties = properties;
    }

    public void publish(String routingKey, String payloadJson, Map<String, Object> headers) {
        try {
            Connection conn = getOrCreateConnection();
            // A fresh channel per publish keeps confirm/return state
            // isolated per message; channels are cheap to open.
            try (Channel channel = conn.createChannel()) {
                AtomicBoolean returned = new AtomicBoolean(false);
                AtomicReference<String> returnReason = new AtomicReference<>();
                channel.addReturnListener((replyCode, replyText, exchange, rk, props, body) -> {
                    returned.set(true);
                    returnReason.set(replyCode + " " + replyText + " (" + exchange + "/" + rk + ")");
                });
                // Enable publisher confirms so an async broker nack
                // (e.g. a reject-publish overflow on a full task queue)
                // surfaces synchronously below.
                channel.confirmSelect();

                AMQP.BasicProperties messageProperties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .headers(headers)
                    .build();
                channel.basicPublish(
                    TASK_EXCHANGE,
                    routingKey,
                    true,
                    messageProperties,
                    payloadJson.getBytes(StandardCharsets.UTF_8)
                );

                // Blocks until the broker acks; throws on nack/timeout.
                // A basic.return for a mandatory, unroutable message is
                // delivered before the confirm, so the returned flag is
                // reliably set by the time this returns.
                channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);

                if (returned.get()) {
                    throw new IllegalStateException(
                        "message unroutable: " + TASK_EXCHANGE + "/" + routingKey
                            + " — " + returnReason.get());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish RabbitMQ message", e);
        }
    }

    /**
     * Return the shared connection, opening it on first use and
     * recreating it if it has been closed. Synchronized so concurrent
     * publishers don't race to open duplicate connections.
     */
    private synchronized Connection getOrCreateConnection() throws Exception {
        Connection existing = this.connection;
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        if (existing != null) {
            // Stale (closed) connection — drop the reference and reopen.
            try {
                existing.close();
            } catch (Exception ignored) {
                // best-effort; we're replacing it anyway
            }
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.host());
        factory.setPort(properties.port());
        factory.setUsername(properties.username());
        factory.setPassword(properties.password());
        factory.setVirtualHost(properties.resolvedVirtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        Connection created = factory.newConnection("meeting-api-outbox-publisher");
        this.connection = created;
        return created;
    }

    @PreDestroy
    public synchronized void shutdown() {
        Connection existing = this.connection;
        this.connection = null;
        if (existing == null) {
            return;
        }
        try {
            if (existing.isOpen()) {
                existing.close();
            }
        } catch (Exception ex) {
            log.warn("rabbitmq_publisher_close_failed reason={}", ex.getMessage());
        }
    }
}
