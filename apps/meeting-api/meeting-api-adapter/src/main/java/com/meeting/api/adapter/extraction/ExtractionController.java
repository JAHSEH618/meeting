package com.meeting.api.adapter.extraction;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.extraction.ActionItemDTO;
import com.meeting.api.client.extraction.DecisionDTO;
import com.meeting.api.client.extraction.ExtractionFacade;
import com.meeting.api.client.extraction.RiskDTO;
import com.meeting.api.client.extraction.UpdateAcceptanceCommand;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
public class ExtractionController {
    private final ExtractionFacade facade;

    public ExtractionController(ExtractionFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/meetings/{meetingId}/action-items")
    public ResponseEntity<ApiResponse<List<ActionItemDTO>>> listActionItems(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var items = facade.listActionItems(TenantContextHolder.currentTenantId(), meetingId);
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
    }

    @GetMapping("/api/meetings/{meetingId}/decisions")
    public ResponseEntity<ApiResponse<List<DecisionDTO>>> listDecisions(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var items = facade.listDecisions(TenantContextHolder.currentTenantId(), meetingId);
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
    }

    @GetMapping("/api/meetings/{meetingId}/risks")
    public ResponseEntity<ApiResponse<List<RiskDTO>>> listRisks(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var items = facade.listRisks(TenantContextHolder.currentTenantId(), meetingId);
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
    }

    @PostMapping("/api/meetings/{meetingId}/action-items/{itemId}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptActionItem(
        @PathVariable String meetingId,
        @PathVariable String itemId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return updateAcceptance(meetingId, itemId, "ACTION_ITEM", "ACCEPTED", requestId, traceId, idempotencyKey, userId);
    }

    @PostMapping("/api/meetings/{meetingId}/action-items/{itemId}/reject")
    public ResponseEntity<ApiResponse<Void>> rejectActionItem(
        @PathVariable String meetingId,
        @PathVariable String itemId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return updateAcceptance(meetingId, itemId, "ACTION_ITEM", "REJECTED", requestId, traceId, idempotencyKey, userId);
    }

    @PostMapping("/api/meetings/{meetingId}/decisions/{itemId}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptDecision(
        @PathVariable String meetingId,
        @PathVariable String itemId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return updateAcceptance(meetingId, itemId, "DECISION", "ACCEPTED", requestId, traceId, idempotencyKey, userId);
    }

    @PostMapping("/api/meetings/{meetingId}/decisions/{itemId}/reject")
    public ResponseEntity<ApiResponse<Void>> rejectDecision(
        @PathVariable String meetingId,
        @PathVariable String itemId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return updateAcceptance(meetingId, itemId, "DECISION", "REJECTED", requestId, traceId, idempotencyKey, userId);
    }

    @PostMapping("/api/meetings/{meetingId}/risks/{itemId}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptRisk(
        @PathVariable String meetingId,
        @PathVariable String itemId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return updateAcceptance(meetingId, itemId, "RISK", "ACCEPTED", requestId, traceId, idempotencyKey, userId);
    }

    @PostMapping("/api/meetings/{meetingId}/risks/{itemId}/reject")
    public ResponseEntity<ApiResponse<Void>> rejectRisk(
        @PathVariable String meetingId,
        @PathVariable String itemId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return updateAcceptance(meetingId, itemId, "RISK", "REJECTED", requestId, traceId, idempotencyKey, userId);
    }

    private ResponseEntity<ApiResponse<Void>> updateAcceptance(
        String meetingId, String itemId, String kind, String acceptance,
        String requestId, String traceId, String idempotencyKey, String userId
    ) {
        facade.updateAcceptance(new UpdateAcceptanceCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            itemId,
            kind,
            acceptance,
            userId,
            requestId,
            traceId,
            idempotencyKey
        ));
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }
}
