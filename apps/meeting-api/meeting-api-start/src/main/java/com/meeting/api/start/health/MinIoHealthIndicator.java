package com.meeting.api.start.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.c — pings MinIO's {@code /minio/health/live} endpoint with a
 * short timeout. We deliberately skip a write-byte smoke here to keep
 * the probe cheap; a real write would touch billing on hosted object
 * storage and is better done by an out-of-band lifecycle check.
 */
@Component("minio")
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
