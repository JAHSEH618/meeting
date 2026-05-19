package com.meeting.api.adapter.meeting;

import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.meeting.GlossaryTermDTO;
import com.meeting.api.client.meeting.MeetingGlossaryDTO;
import com.meeting.api.client.meeting.MeetingGlossaryFacade;
import com.meeting.api.client.meeting.UpdateMeetingGlossaryCommand;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workstation D2 — meeting-scoped glossary management. */
@RestController
@RequestMapping("/api/meetings/{meetingId}/glossary")
public class MeetingGlossaryController {
    private final MeetingGlossaryFacade facade;

    public MeetingGlossaryController(MeetingGlossaryFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MeetingGlossaryDTO>> get(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return facade.get(TenantContextHolder.currentTenantId(), meetingId)
            .map(dto -> ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping
    public ApiResponse<MeetingGlossaryDTO> update(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody UpdateRequest request
    ) {
        MeetingGlossaryDTO dto = facade.update(new UpdateMeetingGlossaryCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            request.terms() == null ? List.of() : request.terms(),
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(dto, requestId, traceId);
    }

    public record UpdateRequest(List<GlossaryTermDTO> terms) {
    }
}
