package com.meeting.api.adapter.rag;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.rag.RagAnswerDTO;
import com.meeting.api.client.rag.RagQueryCommand;
import com.meeting.api.client.rag.RagQueryFacade;
import com.meeting.api.client.rag.RagQueryScope;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/rag/query}. Translates the public DTO into a fully
 * validated {@link RagQueryCommand} and delegates to
 * {@link RagQueryFacade}. The orchestration (embed → search → fuse →
 * authorize → rerank → LLM) lives in the application layer; this
 * controller is intentionally a thin shell.
 *
 * <p>Tenant + user identity come from {@link TenantContextHolder}
 * (populated by the auth filter from JWT claims).
 *
 * <p>Rate limiting (Phase 8 final-check.md B2): a per-(tenant,user)
 * token bucket guards GPU rerank cost. When exceeded the controller
 * throws {@code ApplicationException(RAG_RATE_LIMITED, 429)} which the
 * {@code MeetingControllerAdvice} maps to a standard error envelope.
 */
@RestController
public class RagQueryController {

    private static final int DEFAULT_TOP_N = 8;

    private final RagQueryFacade facade;
    private final RagRateLimiter rateLimiter;
    private final MeetingApiMetrics metrics;

    public RagQueryController(
        RagQueryFacade facade,
        RagRateLimiter rateLimiter,
        MeetingApiMetrics metrics
    ) {
        this.facade = facade;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @PostMapping("/api/rag/query")
    public ResponseEntity<ApiResponse<RagAnswerDTO>> query(
        @RequestBody RagQueryRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        String tenantId = TenantContextHolder.currentTenantId();
        String currentUserId = TenantContextHolder.currentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new IllegalStateException("User context is not set — RAG query requires authentication");
        }

        if (!rateLimiter.tryAcquire(tenantId, currentUserId)) {
            metrics.ragRateLimitBlocksCounter("tenant_user").increment();
            throw new ApplicationException(
                ErrorCode.RAG_RATE_LIMITED, 429,
                "rag query rate exceeded — retry after a short backoff",
                true
            );
        }

        RagQueryScope scope = body.scope() == null
            ? RagQueryScope.EMPTY
            : new RagQueryScope(
                body.scope().meetingIds() == null ? List.of() : body.scope().meetingIds(),
                body.scope().documentIds() == null ? List.of() : body.scope().documentIds()
            );

        RagAnswerDTO dto = facade.query(new RagQueryCommand(
            tenantId,
            currentUserId,
            body.question(),
            scope,
            body.topN() == null ? DEFAULT_TOP_N : body.topN(),
            body.includeStale() != null && body.includeStale(),
            requestId,
            traceId
        ));
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }
}
