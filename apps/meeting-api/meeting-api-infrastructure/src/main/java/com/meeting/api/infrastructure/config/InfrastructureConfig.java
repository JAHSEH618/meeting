package com.meeting.api.infrastructure.config;

import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RabbitMqProperties.class)
public class InfrastructureConfig {
}
