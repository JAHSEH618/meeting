package com.meeting.api.domain.audit;

import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditActorType;
import com.meeting.api.client.enums.AuditResult;
import java.util.Map;

/**
 * Domain port for writing rows into {@code audit_events}.
 *
 * <p>Every compliance-relevant write (legal-hold place / release,
 * deletion job request / execute, break-glass request / approve /
 * reject / access, export create / revoke) should call this logger
 * in the same transaction as the business write. Audit failures must
 * propagate — never swallow — so the security-critical record is
 * never silently lost.
 */
public interface AuditEventLogger {

    /** Append one audit event. */
    void log(AuditEntry entry);

    /**
     * Self-contained audit record. The application layer assembles all
     * fields from the request context (TenantScopedTransaction has the
     * tenant id + user id; the trace id comes off the request header).
     */
    record AuditEntry(
        String tenantId,
        String actorUserId,
        AuditActorType actorType,
        AuditAction action,
        String resourceType,
        String resourceId,
        AuditResult result,
        Map<String, Object> payload,
        String reason,
        String traceId
    ) {

        public static AuditEntry success(
            String tenantId, String actorUserId,
            AuditAction action, String resourceType, String resourceId,
            Map<String, Object> payload, String traceId
        ) {
            return new AuditEntry(
                tenantId, actorUserId, AuditActorType.USER,
                action, resourceType, resourceId,
                AuditResult.SUCCESS, payload, null, traceId
            );
        }

        public static AuditEntry blocked(
            String tenantId, String actorUserId,
            AuditAction action, String resourceType, String resourceId,
            String reason, String traceId
        ) {
            return new AuditEntry(
                tenantId, actorUserId, AuditActorType.USER,
                action, resourceType, resourceId,
                AuditResult.BLOCKED, Map.of(), reason, traceId
            );
        }
    }
}
