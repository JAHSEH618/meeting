package com.meeting.api.adapter.speaker;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.app.speaker.MeetingSpeakerApplicationService;
import com.meeting.api.client.common.ApiResponse;
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
    public ResponseEntity<ApiResponse<List<MeetingSpeakerDTO>>> list(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var items = service.list(TenantContextHolder.currentTenantId(), meetingId);
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
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
}
