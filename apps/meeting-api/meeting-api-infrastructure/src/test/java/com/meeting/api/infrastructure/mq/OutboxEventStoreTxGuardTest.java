package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox guarantee is "event row commits atomically with the business
 * write" — which only holds inside a transaction. These tests pin the
 * fail-loudly guard so a future caller can't silently append outside one.
 */
class OutboxEventStoreTxGuardTest {

    @Test
    void appendOutsideActiveTransactionFailsLoudly() {
        OutboxEventStore store = new OutboxEventStore(new JdbcTemplate(), new ObjectMapper());

        assertThatThrownBy(() -> store.append(event()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction")
            .hasMessageContaining("TestEvent");
    }

    @Test
    void appendPassesTheGuardWhenATransactionIsActive() {
        OutboxEventStore store = new OutboxEventStore(new JdbcTemplate(), new ObjectMapper());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // Past the guard, the DataSource-less JdbcTemplate is the next
            // failure — proving the guard itself let the call through.
            assertThatThrownBy(() -> store.append(event()))
                .hasMessageContaining("DataSource");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static DomainEvent event() {
        return new DomainEvent() {
            @Override public String eventId() { return "evt_test"; }
            @Override public String eventType() { return "TestEvent"; }
            @Override public String aggregateType() { return "ProcessingTask"; }
            @Override public String aggregateId() { return "task_01"; }
            @Override public String tenantId() { return "tenant_01"; }
            @Override public long sequenceNo() { return 1L; }
            @Override public OffsetDateTime occurredAt() { return OffsetDateTime.parse("2026-07-12T09:00:00Z"); }
            @Override public String payloadVersion() { return "1"; }
            @Override public Map<String, Object> payload() { return Map.of(); }
        };
    }
}
