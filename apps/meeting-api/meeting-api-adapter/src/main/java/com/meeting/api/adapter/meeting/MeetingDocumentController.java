package com.meeting.api.adapter.meeting;

import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.client.meeting.AttachMeetingDocumentCommand;
import com.meeting.api.client.meeting.DetachMeetingDocumentCommand;
import com.meeting.api.client.meeting.MeetingDocumentDTO;
import com.meeting.api.client.meeting.MeetingDocumentFacade;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workstation D1 — REST surface for attach / detach / list meeting documents. */
@RestController
@RequestMapping("/api/meetings/{meetingId}/documents")
public class MeetingDocumentController {
    private final MeetingDocumentFacade facade;

    public MeetingDocumentController(MeetingDocumentFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public ApiResponse<List<MeetingDocumentDTO>> list(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        List<MeetingDocumentDTO> items = facade.list(TenantContextHolder.currentTenantId(), meetingId);
        return ApiResponse.ok(items, requestId, traceId);
    }

    @PostMapping
    public ApiResponse<MeetingDocumentDTO> attach(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody AttachRequest request
    ) {
        MeetingDocumentDTO dto = facade.attach(new AttachMeetingDocumentCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            request.documentId(),
            request.role(),
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(dto, requestId, traceId);
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> detach(
        @PathVariable String meetingId,
        @PathVariable String documentId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        facade.detach(new DetachMeetingDocumentCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            documentId,
            TenantContextHolder.currentUserId(),
            requestId,
            traceId
        ));
        return ApiResponse.ok(null, requestId, traceId);
    }

    public record AttachRequest(String documentId, DocumentRole role) {
    }
}
