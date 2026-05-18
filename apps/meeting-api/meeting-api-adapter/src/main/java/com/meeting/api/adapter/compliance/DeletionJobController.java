package com.meeting.api.adapter.compliance;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.compliance.CreateDeletionJobCommand;
import com.meeting.api.client.compliance.DeletionJobDTO;
import com.meeting.api.client.compliance.DeletionJobFacade;
import com.meeting.api.client.compliance.DeletionJobFacade.DeletionCertificateDTO;
import com.meeting.api.client.enums.DeletionScopeType;
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
 * Phase 7.3 deletion-job admin endpoints. Maps to the OpenAPI spec at
 * {@code /admin/deletion-jobs}.
 *
 * <ul>
 *   <li>{@code GET    /admin/deletion-jobs}                       list</li>
 *   <li>{@code POST   /admin/deletion-jobs}                       create (202 Accepted)</li>
 *   <li>{@code GET    /admin/deletion-jobs/{jobId}}               get</li>
 * </ul>
 *
 * <p>Certificate retrieval ({@code /admin/deletion-jobs/{jobId}/certificate})
 * comes online when the runner / certificate generator lands.
 */
@RestController
public class DeletionJobController {

    private final DeletionJobFacade facade;

    public DeletionJobController(DeletionJobFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/admin/deletion-jobs")
    public ResponseEntity<ApiResponse<PageResult<DeletionJobDTO>>> list(
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        PageResult<DeletionJobDTO> page = facade.list(
            TenantContextHolder.currentTenantId(), cursor, limit
        );
        return ResponseEntity.ok(ApiResponse.ok(page, requestId, traceId));
    }

    @PostMapping("/api/admin/deletion-jobs")
    public ResponseEntity<ApiResponse<DeletionJobDTO>> create(
        @RequestBody CreateDeletionJobRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        DeletionJobDTO dto = facade.create(new CreateDeletionJobCommand(
            TenantContextHolder.currentTenantId(),
            body.scopeType(),
            body.scopeId(),
            body.reason(),
            userId == null || userId.isBlank() ? "anonymous" : userId,
            body.approvedBy(),
            requestId,
            traceId
        ));
        // OpenAPI says 200; we keep 202 for the async-create semantic
        // since deletion executors aren't synchronous. Wrappers will
        // accept either status code (both is2xxSuccessful).
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(dto, requestId, traceId));
    }

    @GetMapping("/api/admin/deletion-jobs/{jobId}")
    public ResponseEntity<ApiResponse<DeletionJobDTO>> get(
        @PathVariable String jobId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        DeletionJobDTO dto = facade.get(TenantContextHolder.currentTenantId(), jobId)
            .orElseThrow(() -> new IllegalArgumentException("deletion job not found: " + jobId));
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    @GetMapping("/api/admin/deletion-jobs/{jobId}/certificate")
    public ResponseEntity<ApiResponse<DeletionCertificateDTO>> getCertificate(
        @PathVariable String jobId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        DeletionCertificateDTO dto = facade.getCertificate(TenantContextHolder.currentTenantId(), jobId)
            .orElseThrow(() -> new IllegalArgumentException(
                "deletion certificate not found (job may not have reached a terminal status yet): " + jobId
            ));
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    public record CreateDeletionJobRequest(
        DeletionScopeType scopeType,
        String scopeId,
        String reason,
        String approvedBy
    ) {}
}
