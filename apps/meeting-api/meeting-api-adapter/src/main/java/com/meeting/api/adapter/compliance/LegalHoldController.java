package com.meeting.api.adapter.compliance;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.compliance.CreateLegalHoldCommand;
import com.meeting.api.client.compliance.LegalHoldDTO;
import com.meeting.api.client.compliance.LegalHoldFacade;
import com.meeting.api.client.enums.LegalHoldScopeType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7 legal-hold endpoints. Matches the OpenAPI spec at
 * {@code packages/meeting-contracts/openapi/public-api.yaml}:
 *
 * <ul>
 *   <li>{@code GET    /api/legal-holds}                     list</li>
 *   <li>{@code POST   /api/legal-holds}                     create</li>
 *   <li>{@code GET    /api/legal-holds/{legalHoldId}}       get</li>
 *   <li>{@code DELETE /api/legal-holds/{legalHoldId}}       release (alias)</li>
 *   <li>{@code PUT    /api/legal-holds/{legalHoldId}/release} release</li>
 * </ul>
 */
@RestController
public class LegalHoldController {

    private final LegalHoldFacade facade;

    public LegalHoldController(LegalHoldFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/legal-holds")
    public ResponseEntity<ApiResponse<PageResult<LegalHoldDTO>>> list(
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        PageResult<LegalHoldDTO> page = facade.list(
            TenantContextHolder.currentTenantId(), cursor, limit
        );
        return ResponseEntity.ok(ApiResponse.ok(page, requestId, traceId));
    }

    @PostMapping("/api/legal-holds")
    public ResponseEntity<ApiResponse<LegalHoldDTO>> create(
        @RequestBody CreateLegalHoldRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String currentUserId = TenantContextHolder.currentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new IllegalStateException("User context is not set — legal hold requires authentication");
        }
        LegalHoldDTO dto = facade.create(new CreateLegalHoldCommand(
            TenantContextHolder.currentTenantId(),
            body.scopeType(),
            body.scopeId(),
            body.reason(),
            currentUserId,
            body.approvedBy(),
            requestId,
            traceId
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(dto, requestId, traceId));
    }

    @GetMapping("/api/legal-holds/{legalHoldId}")
    public ResponseEntity<ApiResponse<LegalHoldDTO>> get(
        @PathVariable String legalHoldId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        LegalHoldDTO dto = facade.get(TenantContextHolder.currentTenantId(), legalHoldId)
            .orElseThrow(() -> new IllegalArgumentException("legal hold not found: " + legalHoldId));
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    @DeleteMapping("/api/legal-holds/{legalHoldId}")
    public ResponseEntity<ApiResponse<Void>> delete(
        @PathVariable String legalHoldId,
        @RequestBody ReleaseLegalHoldRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return release(legalHoldId, body, requestId, traceId);
    }

    @PutMapping("/api/legal-holds/{legalHoldId}/release")
    public ResponseEntity<ApiResponse<Void>> putRelease(
        @PathVariable String legalHoldId,
        @RequestBody(required = false) ReleaseLegalHoldRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return release(legalHoldId, body, requestId, traceId);
    }

    private ResponseEntity<ApiResponse<Void>> release(
        String legalHoldId, ReleaseLegalHoldRequest body,
        String requestId, String traceId
    ) {
        String currentUserId = TenantContextHolder.currentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new IllegalStateException("User context is not set — legal hold requires authentication");
        }
        String reason = (body == null || body.reason() == null || body.reason().isBlank())
            ? "user-initiated release"
            : body.reason();
        facade.release(
            TenantContextHolder.currentTenantId(),
            legalHoldId,
            currentUserId,
            reason
        );
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    /** Wire-level request bodies kept package-local. */
    public record CreateLegalHoldRequest(
        LegalHoldScopeType scopeType,
        String scopeId,
        String reason,
        String approvedBy
    ) {}

    public record ReleaseLegalHoldRequest(String reason) {}
}
