package com.meeting.api.adapter.audit;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.audit.AuditEventDTO;
import com.meeting.api.client.audit.AuditQueryFacade;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import java.time.OffsetDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/admin/audit-events} — paginated read of audit events
 * with the 90-day window cap enforced inside the application service.
 *
 * <p>Filters: actorUserId, resourceType, resourceId, action, result,
 * from/to (ISO-8601). Pagination via cursor + limit (default 50).
 */
@RestController
public class AuditEventController {

    private final AuditQueryFacade facade;

    public AuditEventController(AuditQueryFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/admin/audit-events")
    public ResponseEntity<ApiResponse<PageResult<AuditEventDTO>>> list(
        @RequestParam(value = "actorUserId", required = false) String actorUserId,
        @RequestParam(value = "resourceType", required = false) String resourceType,
        @RequestParam(value = "resourceId", required = false) String resourceId,
        @RequestParam(value = "action", required = false) String action,
        @RequestParam(value = "result", required = false) String result,
        @RequestParam(value = "from", required = false) String fromIso,
        @RequestParam(value = "to", required = false) String toIso,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        AuditAction actionEnum = action == null || action.isBlank()
            ? null : AuditAction.valueOf(action.trim().toUpperCase());
        AuditResult resultEnum = result == null || result.isBlank()
            ? null : AuditResult.valueOf(result.trim().toUpperCase());
        OffsetDateTime from = fromIso == null || fromIso.isBlank()
            ? null : OffsetDateTime.parse(fromIso);
        OffsetDateTime to = toIso == null || toIso.isBlank()
            ? null : OffsetDateTime.parse(toIso);

        PageResult<AuditEventDTO> page = facade.query(new AuditQueryFacade.AuditQueryRequest(
            TenantContextHolder.currentTenantId(),
            actorUserId, resourceType, resourceId,
            actionEnum, resultEnum,
            from, to, cursor, limit
        ));
        return ResponseEntity.ok(ApiResponse.ok(page, requestId, traceId));
    }
}
