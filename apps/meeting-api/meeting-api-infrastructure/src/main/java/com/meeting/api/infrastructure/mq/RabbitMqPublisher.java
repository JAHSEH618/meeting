package com.meeting.api.infrastructure.mq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RabbitMqPublisher {
    public static final String TASK_EXCHANGE = "meeting.task.exchange";

    private final RabbitMqProperties properties;

    public RabbitMqPublisher(RabbitMqProperties properties) {
        this.properties = properties;
    }

    public void publish(String routingKey, String payloadJson, Map<String, Object> headers) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.host());
        factory.setPort(properties.port());
        factory.setUsername(properties.username());
        factory.setPassword(properties.password());
        factory.setVirtualHost(properties.resolvedVirtualHost());
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
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
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish RabbitMQ message", e);
        }
    }
}
