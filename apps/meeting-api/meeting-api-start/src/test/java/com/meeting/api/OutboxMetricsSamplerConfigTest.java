package com.meeting.api;

import com.meeting.api.start.config.OutboxMetricsSamplerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

class OutboxMetricsSamplerConfigTest {

    @Test
    void publishesBacklogAndOldestPendingGauges() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForMap(anyString()))
            .thenReturn(Map.of("backlog", 42L, "oldest_seconds", 17L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetricsSamplerConfig sampler = new OutboxMetricsSamplerConfig(jdbc, registry);

        sampler.sample();

        assertThat(registry.get("meeting.api.outbox.backlog").gauge().value()).isEqualTo(42.0);
        assertThat(registry.get("meeting.api.outbox.oldest_pending_seconds").gauge().value())
            .isEqualTo(17.0);
    }

    @Test
    void keepsPreviousValuesWhenSamplingFails() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForMap(anyString()))
            .thenReturn(Map.of("backlog", 42L, "oldest_seconds", 17L))
            .thenThrow(new RuntimeException("connection refused"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetricsSamplerConfig sampler = new OutboxMetricsSamplerConfig(jdbc, registry);

        sampler.sample();
        sampler.sample();

        assertThat(registry.get("meeting.api.outbox.backlog").gauge().value()).isEqualTo(42.0);
        assertThat(registry.get("meeting.api.outbox.oldest_pending_seconds").gauge().value())
            .isEqualTo(17.0);
    }

    @Test
    void gaugesStartAtZeroBeforeFirstSample() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxMetricsSamplerConfig(jdbc, registry);

        assertThat(registry.get("meeting.api.outbox.backlog").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("meeting.api.outbox.oldest_pending_seconds").gauge().value())
            .isEqualTo(0.0);
    }
}
