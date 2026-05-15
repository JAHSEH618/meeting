package com.meeting.api.infrastructure.config;

import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalProperties;
import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    RabbitMqProperties.class,
    AiWorkerInternalProperties.class
})
public class InfrastructureConfig {
}
