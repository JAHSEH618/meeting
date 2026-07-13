package com.meeting.api.start.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Samples {@code domain_events_outbox} into the two gauges the Prometheus
 * rules alert on: {@code meeting_api_outbox_backlog} (PENDING rows) and
 * {@code meeting_api_outbox_oldest_pending_seconds} (age of the oldest
 * PENDING row). The alert rules predate this sampler and used to query
 * gauges nothing emitted; the only backlog signal was the
 * {@code outboxBacklog} HealthIndicator, which Prometheus never scrapes.
 *
 * <p>On a sampling failure the previous values are left in place rather
 * than zeroed — a DB blip must not silently clear an active backlog alert.
 *
 * <p>Disable with {@code meeting.outbox-metrics.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "meeting.outbox-metrics",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class OutboxMetricsSamplerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxMetricsSamplerConfig.class);

    private static final String SAMPLE_SQL =
        """
        SELECT count(*)::bigint AS backlog,
               COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::bigint AS oldest_seconds
          FROM domain_events_outbox
         WHERE status = 'PENDING'
        """;

    private final JdbcTemplate jdbc;
    private final AtomicLong backlog = new AtomicLong(0);
    private final AtomicLong oldestPendingSeconds = new AtomicLong(0);

    public OutboxMetricsSamplerConfig(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("meeting.api.outbox.backlog", backlog, AtomicLong::get)
            .description("PENDING rows in domain_events_outbox")
            .register(registry);
        Gauge.builder("meeting.api.outbox.oldest_pending_seconds", oldestPendingSeconds, AtomicLong::get)
            .description("Age in seconds of the oldest PENDING outbox event")
            .register(registry);
    }

    @Scheduled(
        fixedDelayString = "${meeting.outbox-metrics.interval-ms:30000}",
        initialDelayString = "${meeting.outbox-metrics.initial-delay-ms:15000}"
    )
    public void sample() {
        try {
            Map<String, Object> row = jdbc.queryForMap(SAMPLE_SQL);
            backlog.set(((Number) row.get("backlog")).longValue());
            oldestPendingSeconds.set(((Number) row.get("oldest_seconds")).longValue());
        } catch (RuntimeException cause) {
            LOG.warn("outbox_metrics_sample_failed", cause);
        }
    }
}
