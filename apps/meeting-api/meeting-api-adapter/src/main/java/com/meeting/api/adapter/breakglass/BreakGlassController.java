package com.meeting.api.adapter.breakglass;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.breakglass.BreakGlassFacade;
import com.meeting.api.client.breakglass.BreakGlassRequestDTO;
import com.meeting.api.client.breakglass.CreateBreakGlassCommand;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.BreakGlassStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7.4 break-glass admin endpoints. Maps to the OpenAPI spec at
 * {@code /admin/break-glass/requests}.
 */
@RestController
public class BreakGlassController {

    private final BreakGlassFacade facade;

    public BreakGlassController(BreakGlassFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/admin/break-glass/requests")
    public ResponseEntity<ApiResponse<PageResult<BreakGlassRequestDTO>>> list(
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        BreakGlassStatus filter = status == null || status.isBlank()
            ? null : BreakGlassStatus.valueOf(status.trim().toUpperCase());
        PageResult<BreakGlassRequestDTO> page = facade.list(
            TenantContextHolder.currentTenantId(), filter, cursor, limit
        );
        return ResponseEntity.ok(ApiResponse.ok(page, requestId, traceId));
    }

    @PostMapping("/api/admin/break-glass/requests")
    public ResponseEntity<ApiResponse<BreakGlassRequestDTO>> create(
        @RequestBody CreateBreakGlassBody body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String currentUserId = TenantContextHolder.currentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new IllegalStateException("User context is not set — break-glass requires authentication");
        }
        BreakGlassRequestDTO dto = facade.create(new CreateBreakGlassCommand(
            TenantContextHolder.currentTenantId(),
            body.scopeType(),
            body.scopeId(),
            body.reason(),
            currentUserId,
            requestId,
            traceId
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(dto, requestId, traceId));
    }

    @PostMapping("/api/admin/break-glass/requests/{requestId}/approve")
    public ResponseEntity<ApiResponse<BreakGlassRequestDTO>> approve(
        @PathVariable("requestId") String bgRequestId,
        @RequestHeader("X-Request-Id") String httpRequestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        String currentUserId = TenantContextHolder.currentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new IllegalStateException("User context is not set — break-glass requires authentication");
        }
        BreakGlassRequestDTO dto = facade.approve(
            TenantContextHolder.currentTenantId(),
            bgRequestId,
            currentUserId
        );
        return ResponseEntity.ok(ApiResponse.ok(dto, httpRequestId, traceId));
    }

    @PostMapping("/api/admin/break-glass/requests/{requestId}/reject")
    public ResponseEntity<ApiResponse<BreakGlassRequestDTO>> reject(
        @PathVariable("requestId") String bgRequestId,
        @RequestBody RejectBody body,
        @RequestHeader("X-Request-Id") String httpRequestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            throw new IllegalArgumentException("reject reason is required");
        }
        String currentUserId = TenantContextHolder.currentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new IllegalStateException("User context is not set — break-glass requires authentication");
        }
        BreakGlassRequestDTO dto = facade.reject(
            TenantContextHolder.currentTenantId(),
            bgRequestId,
            currentUserId,
            body.reason()
        );
        return ResponseEntity.ok(ApiResponse.ok(dto, httpRequestId, traceId));
    }

    public record CreateBreakGlassBody(
        String scopeType,
        String scopeId,
        String reason
    ) {}

    public record RejectBody(String reason) {}
}
