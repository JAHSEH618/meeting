package com.meeting.api.infrastructure.persistence.breakglass;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.BreakGlassStatus;
import com.meeting.api.domain.breakglass.BreakGlassRequest;
import com.meeting.api.domain.breakglass.BreakGlassRequestRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBreakGlassRequestRepository implements BreakGlassRequestRepository {

    private final JdbcTemplate jdbc;

    public JdbcBreakGlassRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(BreakGlassRequest req) {
        jdbc.update(
            """
            INSERT INTO break_glass_requests (
              id, tenant_id, requester_id, scope_type, scope_id, reason,
              status, valid_from, valid_until,
              approver_id, approved_at, rejected_at, reject_reason,
              revoked_at, revoked_by, created_at, updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            req.id(),
            req.tenantId(),
            req.requesterId(),
            req.scopeType(),
            req.scopeId(),
            req.reason(),
            req.status().name(),
            ts(req.validFrom()),
            ts(req.validUntil()),
            req.approverId(),
            ts(req.approvedAt()),
            ts(req.rejectedAt()),
            req.rejectReason(),
            ts(req.revokedAt()),
            req.revokedBy(),
            ts(req.createdAt()),
            ts(req.updatedAt())
        );
    }

    @Override
    public void update(BreakGlassRequest req) {
        jdbc.update(
            """
            UPDATE break_glass_requests SET
              status = ?, valid_from = ?, valid_until = ?,
              approver_id = ?, approved_at = ?, rejected_at = ?,
              reject_reason = ?, revoked_at = ?, revoked_by = ?,
              updated_at = ?
            WHERE tenant_id = ? AND id = ?
            """,
            req.status().name(),
            ts(req.validFrom()),
            ts(req.validUntil()),
            req.approverId(),
            ts(req.approvedAt()),
            ts(req.rejectedAt()),
            req.rejectReason(),
            ts(req.revokedAt()),
            req.revokedBy(),
            ts(req.updatedAt()),
            req.tenantId(),
            req.id()
        );
    }

    @Override
    public Optional<BreakGlassRequest> findById(String tenantId, String requestId) {
        List<BreakGlassRequest> rows = jdbc.query(
            "SELECT * FROM break_glass_requests WHERE tenant_id = ? AND id = ?",
            mapper(),
            tenantId, requestId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public PageResult<BreakGlassRequest> listByTenant(
        String tenantId, BreakGlassStatus status, String cursor, int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM break_glass_requests WHERE tenant_id = ?"
        );
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (cursor != null && !cursor.isBlank()) {
            int sep = cursor.indexOf('|');
            if (sep > 0) {
                String createdAtIso = cursor.substring(0, sep);
                String idCursor = cursor.substring(sep + 1);
                sql.append(" AND (created_at, id) < (?, ?)");
                args.add(Timestamp.from(OffsetDateTime.parse(createdAtIso).toInstant()));
                args.add(idCursor);
            }
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(safeLimit + 1);

        List<BreakGlassRequest> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
        boolean hasMore = rows.size() > safeLimit;
        List<BreakGlassRequest> page = hasMore ? rows.subList(0, safeLimit) : rows;
        String nextCursor = null;
        if (hasMore) {
            BreakGlassRequest last = page.get(page.size() - 1);
            nextCursor = last.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString()
                + "|" + last.id();
        }
        return new PageResult<>(page, new PageResult.PageInfo(nextCursor, hasMore, safeLimit));
    }

    @Override
    public List<BreakGlassRequest> claimExpired(String tenantId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query(
            """
            SELECT * FROM break_glass_requests
            WHERE tenant_id = ?
              AND status = 'APPROVED'
              AND valid_until < now()
            ORDER BY valid_until ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            mapper(),
            tenantId, safeLimit
        );
    }

    private RowMapper<BreakGlassRequest> mapper() {
        return (rs, rowNum) -> BreakGlassRequest.builder()
            .id(rs.getString("id"))
            .tenantId(rs.getString("tenant_id"))
            .requesterId(rs.getString("requester_id"))
            .scopeType(rs.getString("scope_type"))
            .scopeId(rs.getString("scope_id"))
            .reason(rs.getString("reason"))
            .status(BreakGlassStatus.valueOf(rs.getString("status")))
            .validFrom(odt(rs, "valid_from"))
            .validUntil(odt(rs, "valid_until"))
            .approverId(rs.getString("approver_id"))
            .approvedAt(odt(rs, "approved_at"))
            .rejectedAt(odt(rs, "rejected_at"))
            .rejectReason(rs.getString("reject_reason"))
            .revokedAt(odt(rs, "revoked_at"))
            .revokedBy(rs.getString("revoked_by"))
            .createdAt(odt(rs, "created_at"))
            .updatedAt(odt(rs, "updated_at"))
            .build();
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp ts(OffsetDateTime at) {
        return at == null ? null : Timestamp.from(at.toInstant());
    }
}
