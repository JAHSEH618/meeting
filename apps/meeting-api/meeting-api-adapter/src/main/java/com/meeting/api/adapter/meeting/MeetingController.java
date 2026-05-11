package com.meeting.api.adapter.meeting;

import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meeting Public API Controller.
 *
 * Design constraints (see spec.md §2.1, §3.1, §7):
 * - TenantId resolved from JWT (by auth filter / TenantContext), NOT from client header.
 * - The X-Tenant-Id header must NOT be accepted with a default value — see E1 fix.
 * - X-Request-Id and X-Trace-Id are required for audit; Idempotency-Key required for mutating ops.
 * - Request DTOs live in meeting-api-client; this controller only does protocol adaptation.
 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingController {
    private final MeetingFacade meetingFacade;

    public MeetingController(MeetingFacade meetingFacade) {
        this.meetingFacade = meetingFacade;
    }

    @PostMapping
    public ApiResponse<MeetingDTO> create(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateMeetingCommand command
    ) {
        // tenantId is injected by TenantContextFilter from JWT, not read from header.
        // This command carries tenantId set by a @RequestAttribute interceptor.
        MeetingDTO meeting = meetingFacade.create(command);
        return ApiResponse.ok(meeting, requestId, traceId);
    }

    @GetMapping
    public ApiResponse<List<MeetingDTO>> list(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        String tenantId = TenantContextHolder.currentTenantId();
        return ApiResponse.ok(meetingFacade.list(tenantId), requestId, traceId);
    }

    @GetMapping("/{meetingId}")
    public ResponseEntity<ApiResponse<MeetingDTO>> get(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @PathVariable String meetingId
    ) {
        String tenantId = TenantContextHolder.currentTenantId();
        return meetingFacade.get(tenantId, meetingId)
            .map(meeting -> ResponseEntity.ok(ApiResponse.ok(meeting, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
