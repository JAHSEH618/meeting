package com.meeting.api.infrastructure.persistence.breakglass;

import com.meeting.api.domain.breakglass.BreakGlassEvaluationPort;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC implementation of {@link BreakGlassEvaluationPort}. Indexed by
 * {@code break_glass_requests_user_idx} (tenant_id, requester_id,
 * status, valid_until) so the hot-path SELECT is a single index hit.
 */
@Component
public class JdbcBreakGlassEvaluationPort implements BreakGlassEvaluationPort {

    private final JdbcTemplate jdbc;

    public JdbcBreakGlassEvaluationPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasActiveAccess(
        String tenantId, String userId,
        String scopeType, String scopeId,
        OffsetDateTime at
    ) {
        Integer count = jdbc.query(
            """
            SELECT 1 FROM break_glass_requests
            WHERE tenant_id = ?
              AND requester_id = ?
              AND scope_type = ?
              AND scope_id = ?
              AND status = 'APPROVED'
              AND valid_from <= ?
              AND valid_until > ?
            LIMIT 1
            """,
            rs -> rs.next() ? 1 : 0,
            tenantId, userId, scopeType, scopeId,
            Timestamp.from(at.toInstant()),
            Timestamp.from(at.toInstant())
        );
        return count != null && count == 1;
    }
}
