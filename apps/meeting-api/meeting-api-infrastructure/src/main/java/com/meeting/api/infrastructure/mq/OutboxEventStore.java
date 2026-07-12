package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.common.DomainEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class OutboxEventStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OutboxEventRecord append(DomainEvent event) {
        // The whole point of the outbox is that the event row commits (or
        // rolls back) atomically with the business write. An append outside
        // a transaction silently loses that guarantee — fail loudly instead.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "outbox append for " + event.eventType()
                    + " outside an active transaction — the outbox row must commit"
                    + " atomically with the business write (wrap the caller in"
                    + " TenantScopedTransaction)"
            );
        }
        long sequenceNo = nextSequenceNo(event.tenantId(), event.aggregateType(), event.aggregateId());
        String id = event.eventId() == null || event.eventId().isBlank()
            ? "evt_" + UUID.randomUUID().toString().replace("-", "")
            : event.eventId();
        String payloadJson = toJson(event.payload());
        String dedupeKey = event.tenantId() + ":" + event.aggregateType() + ":" + event.aggregateId() + ":" + sequenceNo + ":" + event.eventType();
        jdbcTemplate.update(
            """
            INSERT INTO domain_events_outbox (
              id, tenant_id, aggregate_type, aggregate_id, sequence_no,
              event_type, event_version, payload_json, dedupe_key, status, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, 1, ?::jsonb, ?, 'PENDING', ?)
            """,
            id,
            event.tenantId(),
            event.aggregateType(),
            event.aggregateId(),
            sequenceNo,
            event.eventType(),
            payloadJson,
            dedupeKey,
            Timestamp.from(event.occurredAt().toInstant())
        );
        return new OutboxEventRecord(
            id,
            event.tenantId(),
            event.aggregateType(),
            event.aggregateId(),
            sequenceNo,
            event.eventType(),
            payloadJson,
            dedupeKey,
            0,
            event.occurredAt()
        );
    }

    public List<OutboxEventRecord> lockPendingBatch(String tenantId, int batchSize) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, aggregate_type, aggregate_id, sequence_no,
                   event_type, payload_json::text, dedupe_key, retry_count, created_at
              FROM domain_events_outbox
             WHERE tenant_id = ?
               AND status = 'PENDING'
             ORDER BY aggregate_type, aggregate_id, sequence_no
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """,
            this::mapRecord,
            tenantId,
            batchSize
        );
    }

    /**
     * Return the payload JSON of the most recent event of {@code eventType} for
     * an aggregate. Outbox rows are retained after publishing (status flips to
     * PUBLISHED, the row is not deleted), so the original creation payload stays
     * recoverable — used to re-dispatch orphaned tasks without persisting a copy
     * of the message on the task itself.
     */
    public Optional<String> findLatestPayloadJson(
        String tenantId,
        String aggregateType,
        String aggregateId,
        String eventType
    ) {
        return jdbcTemplate.query(
            """
            SELECT payload_json::text
              FROM domain_events_outbox
             WHERE tenant_id = ? AND aggregate_type = ? AND aggregate_id = ? AND event_type = ?
             ORDER BY sequence_no DESC
             LIMIT 1
            """,
            rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty(),
            tenantId,
            aggregateType,
            aggregateId,
            eventType
        );
    }

    public void markPublished(String id) {
        jdbcTemplate.update(
            "UPDATE domain_events_outbox SET status = 'PUBLISHED', published_at = now() WHERE id = ?",
            id
        );
    }

    /**
     * Record a publish failure: bump {@code retry_count} and either keep the
     * row {@code PENDING} for the next poll or flip it to terminal {@code DLQ}
     * once the retry budget is exhausted.
     *
     * @return {@code true} if this failure moved the event to {@code DLQ}
     */
    public boolean markFailed(String id, String errorCode, String errorMessage, int maxRetries) {
        String status = jdbcTemplate.query(
            """
            UPDATE domain_events_outbox
               SET retry_count = retry_count + 1,
                   last_error_code = ?,
                   last_error_message = ?,
                   status = CASE WHEN retry_count + 1 >= ? THEN 'DLQ' ELSE 'PENDING' END
             WHERE id = ?
             RETURNING status
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            errorCode,
            errorMessage,
            maxRetries,
            id
        );
        return "DLQ".equals(status);
    }

    /**
     * Terminal-status update for events that have no destination
     * (recorded for audit but not routed to RabbitMQ — e.g. internal
     * domain events the Java side already handled via Spring
     * {@code ApplicationEventPublisher}). Does not increment
     * {@code retry_count}, so SKIPPED rows stay out of the failure
     * dashboards.
     */
    public void markSkipped(String id, String reason) {
        jdbcTemplate.update(
            """
            UPDATE domain_events_outbox
               SET status = 'SKIPPED',
                   last_error_code = 'OUTBOX_SKIPPED',
                   last_error_message = ?,
                   published_at = now()
             WHERE id = ?
            """,
            reason,
            id
        );
    }

    /**
     * Terminal-status update for unroutable events (unknown event type
     * with no allow-list entry). Sends straight to DLQ — retrying won't
     * change the verdict, and on-call should see it once, not every poll.
     */
    public void markUnroutable(String id, String reason) {
        jdbcTemplate.update(
            """
            UPDATE domain_events_outbox
               SET status = 'DLQ',
                   last_error_code = 'OUTBOX_UNROUTABLE_EVENT_TYPE',
                   last_error_message = ?
             WHERE id = ?
            """,
            reason,
            id
        );
    }

    private long nextSequenceNo(String tenantId, String aggregateType, String aggregateId) {
        Long current = jdbcTemplate.query(
            """
            SELECT sequence_no
              FROM domain_events_outbox
             WHERE tenant_id = ? AND aggregate_type = ? AND aggregate_id = ?
             ORDER BY sequence_no DESC
             LIMIT 1
             FOR UPDATE
            """,
            rs -> rs.next() ? rs.getLong("sequence_no") : null,
            tenantId,
            aggregateType,
            aggregateId
        );
        return current == null ? 1L : current + 1L;
    }

    private OutboxEventRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEventRecord(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getLong("sequence_no"),
            rs.getString("event_type"),
            rs.getString("payload_json"),
            rs.getString("dedupe_key"),
            rs.getInt("retry_count"),
            toOffsetDateTime(rs.getTimestamp("created_at"))
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("domain event payload is not serializable", e);
        }
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
