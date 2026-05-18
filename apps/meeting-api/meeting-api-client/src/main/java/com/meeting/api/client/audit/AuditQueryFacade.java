package com.meeting.api.client.audit;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import java.time.OffsetDateTime;

/**
 * Read-side facade for admin audit-events queries.
 * Enforces the 90-day max-window cap declared in the OpenAPI spec.
 */
public interface AuditQueryFacade {

    PageResult<AuditEventDTO> query(AuditQueryRequest request);

    record AuditQueryRequest(
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

        public AuditQueryRequest {
            java.util.Objects.requireNonNull(tenantId, "tenantId");
            if (limit < 1 || limit > 200) {
                limit = Math.max(1, Math.min(limit, 200));
            }
        }
    }
}
