package com.meeting.api.adapter.export;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.export.CreateExportCommand;
import com.meeting.api.client.export.ExportFacade;
import com.meeting.api.client.export.ExportJobDTO;
import com.meeting.api.client.export.ExportRenderOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 6 export endpoints. Five routes wired up exactly as
 * {@code packages/meeting-contracts/openapi/public-api.yaml} declares:
 *
 * <ul>
 *   <li>{@code POST   /api/meetings/{meetingId}/exports}        — create</li>
 *   <li>{@code GET    /api/meetings/{meetingId}/exports}        — list</li>
 *   <li>{@code GET    /api/exports/{exportId}}                  — get</li>
 *   <li>{@code POST   /api/exports/{exportId}/cancel}           — cancel</li>
 *   <li>{@code POST   /api/exports/{exportId}/revoke-link}      — revoke link</li>
 * </ul>
 *
 * <p>Pure protocol translation — every business decision lives in
 * {@link ExportFacade}. Errors propagate via the existing
 * {@code MeetingControllerAdvice} (it handles {@code ApplicationException}
 * generically, so EXPORT_CONTENT_STALE / LEGAL_HOLD_BLOCKED /
 * EXPORT_ALREADY_FINISHED are mapped to 422 / 423 / 409 automatically).
 */
@RestController
public class ExportController {

    private final ExportFacade facade;

    public ExportController(ExportFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/api/meetings/{meetingId}/exports")
    public ResponseEntity<ApiResponse<ExportJobDTO>> create(
        @PathVariable String meetingId,
        @RequestBody CreateExportRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (body.format() == null) {
            throw new IllegalArgumentException("format is required");
        }
        ExportRenderOptions opts = new ExportRenderOptions(
            body.includeTranscript() == null ? true : body.includeTranscript(),
            body.includeMinutes() == null ? true : body.includeMinutes(),
            body.includeItems() == null ? true : body.includeItems(),
            body.includeSpeakers() == null ? true : body.includeSpeakers()
        );

        ExportJobDTO dto = facade.create(new CreateExportCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            body.format(),
            body.expectedTranscriptVersion() == null ? 0 : body.expectedTranscriptVersion(),
            body.expectedMinutesVersion(),
            body.watermarkText(),
            opts,
            userId == null || userId.isBlank() ? "anonymous" : userId,
            requestId,
            traceId
        ));
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    @GetMapping("/api/meetings/{meetingId}/exports")
    public ResponseEntity<ApiResponse<PageResult<ExportJobDTO>>> list(
        @PathVariable String meetingId,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        PageResult<ExportJobDTO> page = facade.listByMeeting(
            TenantContextHolder.currentTenantId(), meetingId, cursor, limit
        );
        return ResponseEntity.ok(ApiResponse.ok(page, requestId, traceId));
    }

    @GetMapping("/api/exports/{exportId}")
    public ResponseEntity<ApiResponse<ExportJobDTO>> get(
        @PathVariable String exportId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        ExportJobDTO dto = facade.get(TenantContextHolder.currentTenantId(), exportId)
            .orElseThrow(() -> new IllegalArgumentException("export not found: " + exportId));
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    @PostMapping("/api/exports/{exportId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(
        @PathVariable String exportId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        facade.cancel(
            TenantContextHolder.currentTenantId(),
            exportId,
            userId == null || userId.isBlank() ? "anonymous" : userId
        );
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    @PostMapping("/api/exports/{exportId}/revoke-link")
    public ResponseEntity<ApiResponse<Void>> revokeLink(
        @PathVariable String exportId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        facade.revokeLink(
            TenantContextHolder.currentTenantId(),
            exportId,
            userId == null || userId.isBlank() ? "anonymous" : userId
        );
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    /** Wire-level request DTO mirroring OpenAPI's CreateExportRequest. */
    public record CreateExportRequest(
        ExportFormat format,
        Integer expectedTranscriptVersion,
        Integer expectedMinutesVersion,
        Boolean includeTranscript,
        Boolean includeMinutes,
        Boolean includeItems,
        Boolean includeSpeakers,
        String watermarkText
    ) {}
}
