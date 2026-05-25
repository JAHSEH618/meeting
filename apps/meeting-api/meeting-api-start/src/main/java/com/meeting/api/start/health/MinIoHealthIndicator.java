package com.meeting.api.start.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.c — legacy/local MinIO health check. It is only enabled when
 * {@code meeting.storage.type=minio}; Aliyun OSS deployments do not register
 * this indicator and should validate storage with an out-of-band upload smoke.
 */
@Component("minio")
@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinIoHealthIndicator implements HealthIndicator {

    private final URI healthUri;
    private final HttpClient client;

    public MinIoHealthIndicator(
        @Value("${meeting.storage.minio.endpoint:http://localhost:9000}") String endpoint
    ) {
        this.healthUri = URI.create(trimSlash(endpoint) + "/minio/health/live");
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    @Override
    public Health health() {
        try {
            HttpRequest req = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(3))
                .GET().build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() == 200) {
                return Health.up()
                    .withDetail("endpoint", healthUri.toString())
                    .build();
            }
            return Health.down()
                .withDetail("endpoint", healthUri.toString())
                .withDetail("status", res.statusCode())
                .build();
        } catch (Exception ex) {
            return Health.down()
                .withDetail("endpoint", healthUri.toString())
                .withDetail("error", ex.getClass().getSimpleName())
                .withDetail("message", ex.getMessage())
                .build();
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
