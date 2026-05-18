package com.meeting.api;

import com.meeting.api.start.health.OutboxBacklogHealthIndicator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;

class OutboxBacklogHealthIndicatorTest {

    @Test
    void reportsUpWhenBacklogUnderWarnThreshold() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(123L);
        OutboxBacklogHealthIndicator h = new OutboxBacklogHealthIndicator(jdbc, 5_000L, 50_000L);

        Health out = h.health();
        assertThat(out.getStatus()).isEqualTo(Status.UP);
        assertThat(out.getDetails()).containsEntry("backlog", 123L);
        assertThat(out.getDetails()).doesNotContainKey("state");
    }

    @Test
    void reportsUpDegradedWhenBacklogAboveWarn() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(8_000L);
        OutboxBacklogHealthIndicator h = new OutboxBacklogHealthIndicator(jdbc, 5_000L, 50_000L);

        Health out = h.health();
        assertThat(out.getStatus()).isEqualTo(Status.UP);
        assertThat(out.getDetails()).containsEntry("state", "degraded");
        assertThat(out.getDetails()).containsEntry("backlog", 8_000L);
    }

    @Test
    void reportsDownWhenBacklogAboveDownThreshold() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(100_000L);
        OutboxBacklogHealthIndicator h = new OutboxBacklogHealthIndicator(jdbc, 5_000L, 50_000L);

        Health out = h.health();
        assertThat(out.getStatus()).isEqualTo(Status.DOWN);
        assertThat(out.getDetails()).containsEntry("backlog", 100_000L);
    }

    @Test
    void reportsDownOnSqlError() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(anyString(), eq(Long.class)))
            .thenThrow(new RuntimeException("connection refused"));
        OutboxBacklogHealthIndicator h = new OutboxBacklogHealthIndicator(jdbc);

        Health out = h.health();
        assertThat(out.getStatus()).isEqualTo(Status.DOWN);
        assertThat(out.getDetails()).containsKey("error");
        assertThat(out.getDetails()).containsEntry("message", "connection refused");
    }
}
