package com.meeting.api.adapter.transcript;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.transcript.TranscriptDTO;
import com.meeting.api.client.transcript.TranscriptFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings/{meetingId}/transcript")
public class TranscriptController {
    private final TranscriptFacade transcriptFacade;

    public TranscriptController(TranscriptFacade transcriptFacade) {
        this.transcriptFacade = transcriptFacade;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<TranscriptDTO>> get(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return transcriptFacade.get(TenantContextHolder.currentTenantId(), meetingId)
            .map(transcript -> ResponseEntity.ok(ApiResponse.ok(transcript, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
