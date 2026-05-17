package com.meeting.api.infrastructure.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.audit.AuditEventLogger;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC implementation backed by the {@code audit_events} table. Always
 * writes inside the caller's transaction so an audit-write failure
 * surfaces alongside the business operation.
 */
@Component
public class JdbcAuditEventLogger implements AuditEventLogger {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventLogger(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void log(AuditEntry entry) {
        String payloadJson;
        try {
            payloadJson = entry.payload() == null || entry.payload().isEmpty()
                ? "{}"
                : objectMapper.writeValueAsString(entry.payload());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize audit payload", ex);
        }

        jdbc.update(
            """
            INSERT INTO audit_events (
              id, tenant_id, actor_user_id, actor_type, action,
              resource_type, resource_id, result, reason,
              ip_address, user_agent, trace_id, payload_json, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?::inet,?,?,?::jsonb,?)
            """,
            "audit_" + UUID.randomUUID().toString().replace("-", ""),
            entry.tenantId(),
            entry.actorUserId(),
            entry.actorType() == null ? "USER" : entry.actorType().name(),
            entry.action().name(),
            entry.resourceType(),
            entry.resourceId(),
            entry.result().name(),
            entry.reason(),
            /* ip_address */ null,
            /* user_agent */ null,
            entry.traceId(),
            payloadJson,
            Timestamp.from(OffsetDateTime.now().toInstant())
        );
    }
}
