package com.meeting.api.adapter.meeting;

import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {
    private final MeetingFacade meetingFacade;

    public MeetingController(MeetingFacade meetingFacade) {
        this.meetingFacade = meetingFacade;
    }

    @PostMapping
    public ApiResponse<MeetingDTO> create(
        @RequestHeader(value = "X-Tenant-Id", defaultValue = "t_dev") String tenantId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody CreateMeetingRequest request
    ) {
        MeetingDTO meeting = meetingFacade.create(new CreateMeetingCommand(
            tenantId,
            request.title(),
            request.scheduledStartAt(),
            request.securityLevel() == null ? SecurityLevel.INTERNAL : request.securityLevel(),
            request.language() == null ? "zh" : request.language(),
            request.participants() == null ? List.of() : request.participants().stream()
                .map(participant -> new CreateMeetingCommand.ParticipantCommand(
                    participant.personId(),
                    participant.displayName(),
                    participant.role()
                ))
                .toList(),
            null
        ));
        return ApiResponse.ok(meeting, requestId, traceId);
    }

    @GetMapping
    public ApiResponse<List<MeetingDTO>> list(
        @RequestHeader(value = "X-Tenant-Id", defaultValue = "t_dev") String tenantId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
        return ApiResponse.ok(meetingFacade.list(tenantId), requestId, traceId);
    }

    @GetMapping("/{meetingId}")
    public ResponseEntity<ApiResponse<MeetingDTO>> get(
        @RequestHeader(value = "X-Tenant-Id", defaultValue = "t_dev") String tenantId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @PathVariable String meetingId
    ) {
        return meetingFacade.get(tenantId, meetingId)
            .map(meeting -> ResponseEntity.ok(ApiResponse.ok(meeting, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateMeetingRequest(
        String title,
        OffsetDateTime scheduledStartAt,
        SecurityLevel securityLevel,
        String language,
        List<ParticipantRequest> participants
    ) {
    }

    public record ParticipantRequest(
        String personId,
        String displayName,
        String role
    ) {
    }
}
