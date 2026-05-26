package com.meeting.api.start.config;

import com.meeting.api.domain.compliance.KmsKeyDestroyerPort;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.infrastructure.gateway.compliance.NoOpKmsKeyDestroyerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KmsKeyDestroyerConfig {

    @Bean
    @ConditionalOnMissingBean(KmsKeyDestroyerPort.class)
    public KmsKeyDestroyerPort kmsKeyDestroyerPort(SpeakerEmbeddingRepository embeddingRepository) {
        return new NoOpKmsKeyDestroyerPort(embeddingRepository);
    }
}
