package com.meeting.api.start.health;

import com.meeting.api.infrastructure.gateway.aiworker.AiWorkerInternalClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.e — hits ai-worker's {@code GET /internal/health} via the
 * HMAC-protected client. The client itself throws an unwrapped domain
 * exception on 503 / I/O / contract failures; treating any throw as
 * DOWN avoids pulling a Jackson compile dep into meeting-api-start.
 */
@Component("aiWorker")
public class AiWorkerHealthIndicator implements HealthIndicator {

    private final AiWorkerInternalClient client;

    public AiWorkerHealthIndicator(AiWorkerInternalClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            client.call(
                "GET", "/internal/health", /* body */ null,
                "__health_probe__", "req-health", "trace-health", /* timeoutMs */ 2000
            );
            return Health.up().build();
        } catch (Exception ex) {
            return Health.down()
                .withDetail("error", ex.getClass().getSimpleName())
                .withDetail("message", ex.getMessage())
                .build();
        }
    }
}
