package com.meeting.api.domain.audit;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-side port for {@link AuditEventLogger}-produced rows. Phase 7.5
 * exposes only filtered list queries; deletion / retention is owned by
 * a separate cleaner. Implementations must respect RLS via tenant
 * context — no bypass.
 */
public interface AuditEventReadRepository {

    PageResult<AuditEventRow> list(AuditQuery query);

    /** Filter spec for audit queries. All optionals widen the result; non-null narrows. */
    record AuditQuery(
        String tenantId,
        String actorUserId,
        String resourceType,
        String resourceId,
        AuditAction action,
        AuditResult result,
        OffsetDateTime from,
        OffsetDateTime to,
        String cursor,
        int limit
    ) {

        public AuditQuery {
            java.util.Objects.requireNonNull(tenantId, "tenantId");
        }
    }

    /** Materialised view of an audit_events row. Payload is parsed JSON. */
    record AuditEventRow(
        String id,
        String tenantId,
        String actorUserId,
        String actorType,
        AuditAction action,
        String resourceType,
        String resourceId,
        AuditResult result,
        String reason,
        String traceId,
        java.util.Map<String, Object> payload,
        OffsetDateTime createdAt
    ) {
        public AuditEventRow {
            payload = payload == null ? java.util.Map.of() : java.util.Map.copyOf(payload);
        }
    }
}
