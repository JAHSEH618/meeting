package com.meeting.api.adapter.speaker;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.app.speaker.MeetingSpeakerApplicationService;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.speaker.MeetingSpeakerCandidateDTO;
import com.meeting.api.client.speaker.MeetingSpeakerDTO;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeetingSpeakerController {
    private final MeetingSpeakerApplicationService service;

    public MeetingSpeakerController(MeetingSpeakerApplicationService service) {
        this.service = service;
    }

    @GetMapping("/api/meetings/{meetingId}/speakers")
    public ResponseEntity<ApiResponse<MeetingSpeakerListData>> list(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var speakers = service.list(TenantContextHolder.currentTenantId(), meetingId).stream()
            .map(MeetingSpeakerResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.ok(new MeetingSpeakerListData(meetingId, speakers), requestId, traceId));
    }

    @PostMapping("/api/meetings/{meetingId}/speakers/{speakerLabel}/confirm")
    public ResponseEntity<ApiResponse<Void>> confirm(
        @PathVariable String meetingId,
        @PathVariable String speakerLabel,
        @RequestBody ConfirmRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        service.confirm(
            TenantContextHolder.currentTenantId(),
            meetingId,
            speakerLabel,
            body.personId(),
            body.speakerProfileId(),
            body.expectedTranscriptVersion(),
            userId
        );
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    @PostMapping("/api/meetings/{meetingId}/speakers/{speakerLabel}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(
        @PathVariable String meetingId,
        @PathVariable String speakerLabel,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        service.reject(TenantContextHolder.currentTenantId(), meetingId, speakerLabel, userId);
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    public record ConfirmRequest(String personId, String speakerProfileId, Integer expectedTranscriptVersion) {
    }

    public record MeetingSpeakerListData(String meetingId, List<MeetingSpeakerResponse> speakers) {
    }

    public record MeetingSpeakerResponse(
        String speakerLabel,
        String displayName,
        String personId,
        String speakerProfileId,
        String confirmationStatus,
        List<MeetingSpeakerCandidateDTO> candidates
    ) {
        private static MeetingSpeakerResponse from(MeetingSpeakerDTO dto) {
            return new MeetingSpeakerResponse(
                dto.speakerLabel(),
                dto.displayName(),
                dto.personId(),
                dto.speakerProfileId(),
                dto.confirmationStatus(),
                dto.candidates() == null ? List.of() : dto.candidates()
            );
        }
    }
}
