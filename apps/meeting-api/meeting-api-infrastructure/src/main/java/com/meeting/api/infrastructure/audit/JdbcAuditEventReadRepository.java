package com.meeting.api.infrastructure.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import com.meeting.api.domain.audit.AuditEventReadRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditEventReadRepository implements AuditEventReadRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventReadRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<AuditEventRow> list(AuditQuery q) {
        int safeLimit = Math.max(1, Math.min(q.limit(), 200));
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT id, tenant_id, actor_user_id, actor_type, action,
                   resource_type, resource_id, result, reason, trace_id,
                   payload_json, created_at
              FROM audit_events
             WHERE tenant_id = ?
            """);
        args.add(q.tenantId());

        if (q.actorUserId() != null && !q.actorUserId().isBlank()) {
            sql.append(" AND actor_user_id = ?");
            args.add(q.actorUserId());
        }
        if (q.resourceType() != null && !q.resourceType().isBlank()) {
            sql.append(" AND resource_type = ?");
            args.add(q.resourceType());
        }
        if (q.resourceId() != null && !q.resourceId().isBlank()) {
            sql.append(" AND resource_id = ?");
            args.add(q.resourceId());
        }
        if (q.action() != null) {
            sql.append(" AND action = ?");
            args.add(q.action().name());
        }
        if (q.result() != null) {
            sql.append(" AND result = ?");
            args.add(q.result().name());
        }
        if (q.from() != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(q.from().toInstant()));
        }
        if (q.to() != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.from(q.to().toInstant()));
        }
        if (q.cursor() != null && !q.cursor().isBlank()) {
            int sep = q.cursor().indexOf('|');
            if (sep > 0) {
                sql.append(" AND (created_at, id) < (?, ?)");
                args.add(Timestamp.from(OffsetDateTime.parse(q.cursor().substring(0, sep)).toInstant()));
                args.add(q.cursor().substring(sep + 1));
            }
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(safeLimit + 1);

        List<AuditEventRow> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
        boolean hasMore = rows.size() > safeLimit;
        List<AuditEventRow> page = hasMore ? rows.subList(0, safeLimit) : rows;
        String nextCursor = null;
        if (hasMore) {
            AuditEventRow last = page.get(page.size() - 1);
            nextCursor = last.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString()
                + "|" + last.id();
        }
        return new PageResult<>(page, new PageResult.PageInfo(nextCursor, hasMore, safeLimit));
    }

    private RowMapper<AuditEventRow> mapper() {
        return (rs, rowNum) -> new AuditEventRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("actor_user_id"),
            rs.getString("actor_type"),
            parseAction(rs.getString("action")),
            rs.getString("resource_type"),
            rs.getString("resource_id"),
            parseResult(rs.getString("result")),
            rs.getString("reason"),
            rs.getString("trace_id"),
            parsePayload(rs.getString("payload_json")),
            odt(rs, "created_at")
        );
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static AuditAction parseAction(String v) {
        if (v == null) return null;
        try { return AuditAction.valueOf(v); } catch (IllegalArgumentException ex) { return null; }
    }

    private static AuditResult parseResult(String v) {
        if (v == null) return null;
        try { return AuditResult.valueOf(v); } catch (IllegalArgumentException ex) { return null; }
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
