package com.meeting.api.domain.breakglass;

import java.time.OffsetDateTime;

/**
 * Read-side port that answers "is there an active break-glass grant
 * for this user against the given scope right now?".
 *
 * <p>Called from the permission-check layer. When the standard
 * permission check denies access, the layer falls back to this port
 * — a {@code true} result lets the request through and must be
 * accompanied by an audit row
 * ({@link com.meeting.api.client.enums.AuditAction#BREAK_GLASS_ACCESS}).
 *
 * <p>Implementations should be cheap: the read path is on the hot
 * permission check. A 30-second in-memory cache (TTL biased toward
 * freshness) is acceptable.
 */
public interface BreakGlassEvaluationPort {

    /**
     * @param at the moment to evaluate (the {@code valid_from} ≤ at <
     *           {@code valid_until} window check happens here).
     * @return true iff an APPROVED, in-window grant exists for the
     *         triple {@code (tenantId, userId, scopeType, scopeId)}.
     */
    boolean hasActiveAccess(
        String tenantId,
        String userId,
        String scopeType,
        String scopeId,
        OffsetDateTime at
    );
}
