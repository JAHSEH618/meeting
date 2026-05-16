package com.meeting.api.adapter.rag;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.rag.RagReindexFacade;
import com.meeting.api.client.rag.RagReindexResultDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/rag/reindex/meetings/{meetingId}} and
 * {@code POST /api/rag/reindex/documents/{documentId}}.
 *
 * <p>Triggers a synchronous re-chunk of the meeting / document; new
 * chunks land in {@code knowledge_chunks} with {@code embedding=NULL}
 * and a Spring application event fans them into {@code TEXT_EMBEDDING}
 * tasks via the dispatcher. The body of the response carries the count
 * of stale-marked rows and the freshly-created chunk IDs so the caller
 * (web UI / operator script) can decide whether to wait for embeddings.
 */
@RestController
public class RagReindexController {

    private final RagReindexFacade facade;

    public RagReindexController(RagReindexFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/api/rag/reindex/meetings/{meetingId}")
    public ResponseEntity<ApiResponse<RagReindexResultDTO>> reindexMeeting(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        RagReindexResultDTO dto = facade.reindexMeeting(
            TenantContextHolder.currentTenantId(), meetingId, userId
        );
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    @PostMapping("/api/rag/reindex/documents/{documentId}")
    public ResponseEntity<ApiResponse<RagReindexResultDTO>> reindexDocument(
        @PathVariable String documentId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        RagReindexResultDTO dto = facade.reindexDocument(
            TenantContextHolder.currentTenantId(), documentId, userId
        );
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }
}
