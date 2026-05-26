package com.meeting.api.start.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.f — counts {@code domain_events_outbox} rows that are still
 * PENDING after 30 s. A backlog of <5k is UP; 5k-50k is DEGRADED (reported
 * as UP with a {@code state=degraded} detail so probes don't crash
 * yet); >50k is DOWN — the outbox publisher is stuck and the SSE
 * fanout / RabbitMQ routing is silently failing.
 */
@Component("outboxBacklog")
public class OutboxBacklogHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;
    private final long warnThreshold;
    private final long downThreshold;

    @Autowired
    public OutboxBacklogHealthIndicator(JdbcTemplate jdbc) {
        this(jdbc, 5_000L, 50_000L);
    }
    public OutboxBacklogHealthIndicator(JdbcTemplate jdbc, long warnThreshold, long downThreshold) {
        this.jdbc = jdbc;
        this.warnThreshold = warnThreshold;
        this.downThreshold = downThreshold;
    }

    @Override
    public Health health() {
        Long backlog;
        try {
            backlog = jdbc.queryForObject(
                """
                SELECT count(*)::bigint AS c
                  FROM domain_events_outbox
                 WHERE status = 'PENDING'
                   AND created_at < now() - interval '30 seconds'
                """,
                Long.class
            );
        } catch (Exception ex) {
            return Health.down()
                .withDetail("error", ex.getClass().getSimpleName())
                .withDetail("message", ex.getMessage())
                .build();
        }
        long count = backlog == null ? 0L : backlog;
        if (count >= downThreshold) {
            return Health.down()
                .withDetail("backlog", count)
                .withDetail("threshold", downThreshold)
                .build();
        }
        if (count >= warnThreshold) {
            return Health.up()
                .withDetail("state", "degraded")
                .withDetail("backlog", count)
                .withDetail("warnThreshold", warnThreshold)
                .build();
        }
        return Health.up().withDetail("backlog", count).build();
    }
}
