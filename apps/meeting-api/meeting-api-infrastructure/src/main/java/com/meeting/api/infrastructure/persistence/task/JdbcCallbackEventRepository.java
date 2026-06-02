package com.meeting.api.infrastructure.persistence.task;

import com.meeting.api.domain.task.CallbackEventRepository;
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

@Repository
public class JdbcCallbackEventRepository implements CallbackEventRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcCallbackEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CallbackEventRecord> findByIdempotencyKey(String tenantId, String idempotencyKey) {
        List<CallbackEventRecord> records = jdbcTemplate.query(
            """
            SELECT tenant_id, task_id, worker_id, idempotency_key, request_body_hash,
                   attempt_no, lease_owner, response_body_hash, http_status,
                   error_code, trace_id, processed_at
              FROM callback_events
             WHERE tenant_id = ? AND idempotency_key = ?
            """,
            this::mapRecord,
            tenantId,
            idempotencyKey
        );
        return records.stream().findFirst();
    }

    @Override
    public CallbackEventRecord save(CallbackEventRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO callback_events (
              id, tenant_id, task_id, worker_id, attempt_no, lease_owner,
              idempotency_key, request_body_hash, response_body_hash,
              http_status, error_code, trace_id, processed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """,
            "cb_" + UUID.randomUUID().toString().replace("-", ""),
            record.tenantId(),
            record.taskId(),
            record.workerId(),
            record.attemptNo(),
            record.leaseOwner(),
            record.idempotencyKey(),
            record.bodySha256(),
            record.responseSha256(),
            record.httpStatus(),
            record.errorCode(),
            record.traceId(),
            Timestamp.from(record.receivedAt().toInstant())
        );
        return record;
    }

    @Override
    public RecordResult recordOnce(CallbackEventRecord record) {
        int inserted = jdbcTemplate.update(
            """
            INSERT INTO callback_events (
              id, tenant_id, task_id, worker_id, attempt_no, lease_owner,
              idempotency_key, request_body_hash, response_body_hash,
              http_status, error_code, trace_id, processed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """,
            "cb_" + UUID.randomUUID().toString().replace("-", ""),
            record.tenantId(),
            record.taskId(),
            record.workerId(),
            record.attemptNo(),
            record.leaseOwner(),
            record.idempotencyKey(),
            record.bodySha256(),
            record.responseSha256(),
            record.httpStatus(),
            record.errorCode(),
            record.traceId(),
            Timestamp.from(record.receivedAt().toInstant())
        );
        if (inserted == 1) {
            return new RecordResult(RecordStatus.RECORDED, record);
        }
        CallbackEventRecord existing = findByIdempotencyKey(record.tenantId(), record.idempotencyKey())
            .orElseThrow(() -> new IllegalStateException("callback idempotency record disappeared"));
        if (isSameCallback(existing, record)) {
            return new RecordResult(RecordStatus.REPLAYED, existing);
        }
        return new RecordResult(RecordStatus.BODY_HASH_CONFLICT, existing);
    }

    private static boolean isSameCallback(CallbackEventRecord previous, CallbackEventRecord current) {
        return previous.bodySha256().equals(current.bodySha256())
            && previous.taskId().equals(current.taskId())
            && previous.workerId().equals(current.workerId())
            && previous.attemptNo() == current.attemptNo()
            && java.util.Objects.equals(previous.leaseOwner(), current.leaseOwner());
    }

    private CallbackEventRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new CallbackEventRecord(
            rs.getString("tenant_id"),
            rs.getString("task_id"),
            rs.getString("worker_id"),
            rs.getString("idempotency_key"),
            rs.getString("request_body_hash"),
            rs.getInt("attempt_no"),
            rs.getString("lease_owner"),
            rs.getString("response_body_hash"),
            rs.getInt("http_status"),
            rs.getString("error_code"),
            rs.getString("trace_id"),
            toOffsetDateTime(rs.getTimestamp("processed_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
