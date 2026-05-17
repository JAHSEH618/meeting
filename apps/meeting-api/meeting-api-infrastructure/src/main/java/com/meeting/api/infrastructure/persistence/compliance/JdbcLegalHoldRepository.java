package com.meeting.api.infrastructure.persistence.compliance;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.client.enums.LegalHoldStatus;
import com.meeting.api.domain.compliance.LegalHold;
import com.meeting.api.domain.compliance.LegalHoldRepository;
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

/**
 * JDBC implementation of {@link LegalHoldRepository}. Tenant isolation
 * is enforced by RLS; every call must run inside a transaction that
 * has {@code app.tenant_id} set via {@code TenantScopedTransaction}.
 */
@Repository
public class JdbcLegalHoldRepository implements LegalHoldRepository {

    private final JdbcTemplate jdbc;

    public JdbcLegalHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(LegalHold hold) {
        jdbc.update(
            """
            INSERT INTO legal_holds (
              id, tenant_id, scope_type, scope_id, reason,
              requested_by, approved_by, status, created_at,
              released_at, released_by, release_reason
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            hold.id(),
            hold.tenantId(),
            hold.scopeType().name(),
            hold.scopeId(),
            hold.reason(),
            hold.requestedBy(),
            hold.approvedBy(),
            hold.status().name(),
            ts(hold.createdAt()),
            ts(hold.releasedAt()),
            hold.releasedBy(),
            hold.releaseReason()
        );
    }

    @Override
    public void update(LegalHold hold) {
        jdbc.update(
            """
            UPDATE legal_holds SET
              status = ?, released_at = ?, released_by = ?, release_reason = ?
            WHERE tenant_id = ? AND id = ?
            """,
            hold.status().name(),
            ts(hold.releasedAt()),
            hold.releasedBy(),
            hold.releaseReason(),
            hold.tenantId(),
            hold.id()
        );
    }

    @Override
    public Optional<LegalHold> findById(String tenantId, String holdId) {
        List<LegalHold> rows = jdbc.query(
            """
            SELECT * FROM legal_holds WHERE tenant_id = ? AND id = ?
            """,
            mapper(),
            tenantId, holdId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<LegalHold> findActive(
        String tenantId, LegalHoldScopeType scopeType, String scopeId
    ) {
        List<LegalHold> rows = jdbc.query(
            """
            SELECT * FROM legal_holds
            WHERE tenant_id = ? AND scope_type = ? AND scope_id = ?
              AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            """,
            mapper(),
            tenantId, scopeType.name(), scopeId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public PageResult<LegalHold> listByTenant(String tenantId, String cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder sql = new StringBuilder("""
            SELECT * FROM legal_holds WHERE tenant_id = ?
            """);
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

        List<LegalHold> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
        boolean hasMore = rows.size() > safeLimit;
        List<LegalHold> page = hasMore ? rows.subList(0, safeLimit) : rows;
        String nextCursor = null;
        if (hasMore) {
            LegalHold last = page.get(page.size() - 1);
            nextCursor = last.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString()
                + "|" + last.id();
        }
        return new PageResult<>(page, new PageResult.PageInfo(nextCursor, hasMore, safeLimit));
    }

    private RowMapper<LegalHold> mapper() {
        return (rs, rowNum) -> LegalHold.builder()
            .id(rs.getString("id"))
            .tenantId(rs.getString("tenant_id"))
            .scopeType(LegalHoldScopeType.valueOf(rs.getString("scope_type")))
            .scopeId(rs.getString("scope_id"))
            .reason(rs.getString("reason"))
            .requestedBy(rs.getString("requested_by"))
            .approvedBy(rs.getString("approved_by"))
            .status(LegalHoldStatus.valueOf(rs.getString("status")))
            .createdAt(odt(rs, "created_at"))
            .releasedAt(odt(rs, "released_at"))
            .releasedBy(rs.getString("released_by"))
            .releaseReason(rs.getString("release_reason"))
            .build();
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp ts(OffsetDateTime at) {
        return at == null ? null : Timestamp.from(at.toInstant());
    }
}
