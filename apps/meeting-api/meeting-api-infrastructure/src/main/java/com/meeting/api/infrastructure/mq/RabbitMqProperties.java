package com.meeting.api.infrastructure.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.rabbitmq")
public record RabbitMqProperties(
    String host,
    int port,
    String username,
    String password,
    String virtualHost
) {
    public String resolvedVirtualHost() {
        return virtualHost == null || virtualHost.isBlank() ? "/" : virtualHost;
    }
}
