package com.meeting.api.adapter.meeting;

import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingResult;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
        @RequestBody CreateMeetingRequest request
    ) {
        CreateMeetingCommand command = new CreateMeetingCommand(
            TenantContextHolder.currentTenantId(),
            request.title(),
            request.scheduledStartAt(),
            request.securityLevel(),
            request.language(),
            request.participants(),
            TenantContextHolder.currentUserId()
        );
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

    @DeleteMapping("/{meetingId}")
    public ApiResponse<DeleteMeetingResult> delete(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @PathVariable String meetingId,
        @RequestBody(required = false) DeleteMeetingRequest body
    ) {
        String tenantId = TenantContextHolder.currentTenantId();
        String userId = TenantContextHolder.currentUserId();
        DeleteMeetingCommand command = new DeleteMeetingCommand(
            tenantId,
            meetingId,
            userId,
            requestId,
            body == null ? null : body.reason(),
            body == null ? null : body.expectedVersion(),
            body != null && Boolean.TRUE.equals(body.legalHoldAcknowledged())
        );
        DeleteMeetingResult result = meetingFacade.delete(command);
        return ApiResponse.ok(result, requestId, traceId);
    }

    public record CreateMeetingRequest(
        String title,
        java.time.OffsetDateTime scheduledStartAt,
        SecurityLevel securityLevel,
        String language,
        List<CreateMeetingCommand.ParticipantCommand> participants
    ) {
    }

    public record DeleteMeetingRequest(
        String reason,
        Boolean legalHoldAcknowledged,
        Integer expectedVersion
    ) {
    }
}
