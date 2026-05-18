package com.meeting.api.start.health;

import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.b — checks that the required task queues are present in the
 * broker. Missing or temporarily unreachable queues cause the indicator
 * to report DOWN; this is the early signal for misconfigured deployments
 * or a wedged broker.
 */
@Component("rabbitmqQueues")
public class RabbitMqQueueHealthIndicator implements HealthIndicator {

    private static final List<String> REQUIRED_QUEUES = List.of(
        "audio-cpu-queue", "gpu-asr-queue", "gpu-diar-queue",
        "gpu-speaker-queue", "embed-queue", "llm-queue", "export-queue"
    );

    private final RabbitMqProperties properties;

    public RabbitMqQueueHealthIndicator(RabbitMqProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.host());
        factory.setPort(properties.port());
        factory.setUsername(properties.username());
        factory.setPassword(properties.password());
        factory.setVirtualHost(properties.resolvedVirtualHost());
        factory.setConnectionTimeout(3000);

        List<String> missing = new ArrayList<>();
        try (Connection conn = factory.newConnection("meeting-api-health");
             Channel channel = conn.createChannel()) {
            for (String q : REQUIRED_QUEUES) {
                try {
                    channel.queueDeclarePassive(q);
                } catch (Exception ex) {
                    missing.add(q);
                }
            }
        } catch (Exception ex) {
            return Health.down()
                .withDetail("error", "broker_unreachable")
                .withDetail("message", ex.getMessage())
                .build();
        }
        if (!missing.isEmpty()) {
            return Health.down()
                .withDetail("missingQueues", missing)
                .build();
        }
        return Health.up()
            .withDetail("queues", REQUIRED_QUEUES.size())
            .build();
    }
}
