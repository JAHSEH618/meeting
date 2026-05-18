package com.meeting.api.infrastructure.gateway.export;

import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportGatewayRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the domain-layer {@link ExportGatewayRegistry}.
 * Collects every {@link ExportGateway} {@code @Component} on the
 * classpath and hands the list to the registry constructor.
 */
@Configuration
public class ExportGatewayRegistryConfig {

    @Bean
    public ExportGatewayRegistry exportGatewayRegistry(List<ExportGateway> gateways) {
        return new ExportGatewayRegistry(gateways);
    }
}
