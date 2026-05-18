package com.meeting.api.app.breakglass;

import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
import com.meeting.api.domain.breakglass.BreakGlassEvaluationPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Permission-side guard for CONFIDENTIAL / SECRET resources. The
 * caller flow is:
 *
 * <ol>
 *   <li>Standard role-based permission check. If permitted → return.</li>
 *   <li>Otherwise, call {@link #checkAccess} below. If a break-glass
 *       grant is active, the call succeeds and a
 *       {@link AuditAction#BREAK_GLASS_ACCESS} audit row is written.</li>
 *   <li>If no grant is active, the original deny stands.</li>
 * </ol>
 *
 * <p>Audit is written every time {@link #checkAccess} returns
 * {@code true} so every break-glass use is on the trail. Returning
 * {@code false} does NOT write audit — that's the standard deny
 * path's responsibility.
 */
@Service
public class BreakGlassAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassAccessGuard.class);

    private final BreakGlassEvaluationPort evaluator;
    private final AuditEventLogger auditLogger;
    private final Clock clock;

    public BreakGlassAccessGuard(
        BreakGlassEvaluationPort evaluator,
        AuditEventLogger auditLogger
    ) {
        this(evaluator, auditLogger, Clock.systemUTC());
    }

    public BreakGlassAccessGuard(
        BreakGlassEvaluationPort evaluator,
        AuditEventLogger auditLogger,
        Clock clock
    ) {
        this.evaluator = evaluator;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    /**
     * Check whether the user has an active break-glass grant. If yes,
     * record an audit row capturing the access window and return true.
     *
     * @param tenantId   tenant from request context
     * @param userId     authenticated user
     * @param scopeType  e.g. {@code MEETING}, {@code DOCUMENT}, {@code TENANT}
     * @param scopeId    identifier within the scope
     * @param resourcePath optional request path or operation for the audit payload
     * @param traceId    trace id to thread through the audit row
     * @return {@code true} when a break-glass grant lets the access through.
     */
    public boolean checkAccess(
        String tenantId, String userId,
        String scopeType, String scopeId,
        String resourcePath, String traceId
    ) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean granted = evaluator.hasActiveAccess(tenantId, userId, scopeType, scopeId, now);
        if (!granted) {
            return false;
        }
        Map<String, Object> payload = Map.of(
            "scopeType", scopeType,
            "scopeId", scopeId,
            "resourcePath", resourcePath == null ? "" : resourcePath,
            "at", now.toString()
        );
        auditLogger.log(AuditEntry.success(
            tenantId, userId,
            AuditAction.BREAK_GLASS_ACCESS,
            scopeType, scopeId,
            payload,
            traceId
        ));
        log.info(
            "break_glass_access tenant={} user={} scope={}:{} path={}",
            tenantId, userId, scopeType, scopeId, resourcePath
        );
        return true;
    }
}
