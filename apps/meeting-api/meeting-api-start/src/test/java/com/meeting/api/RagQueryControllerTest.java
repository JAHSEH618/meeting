package com.meeting.api;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.meeting.TenantContextMissingException;
import com.meeting.api.adapter.rag.RagQueryController;
import com.meeting.api.adapter.rag.RagQueryRequest;
import com.meeting.api.adapter.rag.RagRateLimiter;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.RagAnswerCoverage;
import com.meeting.api.client.rag.MeetingSegmentCitationDTO;
import com.meeting.api.client.rag.RagAnswerDTO;
import com.meeting.api.client.rag.RagQueryCommand;
import com.meeting.api.client.rag.RagQueryFacade;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagQueryControllerTest {

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void queryFailsClosedWhenTenantContextIsMissing() {
        RagQueryController controller = newController(new StubFacade(), permissiveLimiter());
        assertThatThrownBy(() -> controller.query(
            new RagQueryRequest("q", null, null, null),
            "req_01", "trace_01", null, "user_01"
        )).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void queryAppliesDefaultsAndDelegatesToFacade() {
        StubFacade facade = new StubFacade();
        RagQueryController controller = newController(facade, permissiveLimiter());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ResponseEntity<ApiResponse<RagAnswerDTO>> response = controller.query(
            new RagQueryRequest("How did we decide on roadmap?", null, null, null),
            "req_01", "trace_01", "idem_01", "user_01"
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().answer()).isEqualTo("answer body");

        RagQueryCommand cmd = facade.lastCommand;
        assertThat(cmd.tenantId()).isEqualTo("tenant_01");
        assertThat(cmd.userId()).isEqualTo("user_01");
        assertThat(cmd.topN()).isEqualTo(8);
        assertThat(cmd.includeStale()).isFalse();
        assertThat(cmd.scope().isEmpty()).isTrue();
        assertThat(cmd.requestId()).isEqualTo("req_01");
        assertThat(cmd.traceId()).isEqualTo("trace_01");
    }

    @Test
    void queryParsesScopeHeader() {
        StubFacade facade = new StubFacade();
        RagQueryController controller = newController(facade, permissiveLimiter());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.query(
            new RagQueryRequest(
                "q",
                new RagQueryRequest.Scope(List.of("mtg_1", "mtg_2"), List.of("doc_1")),
                5, true
            ),
            "req_02", "trace_02", "idem_02", "user_42"
        );

        assertThat(facade.lastCommand.userId()).isEqualTo("user_42");
        assertThat(facade.lastCommand.scope().meetingIds()).containsExactly("mtg_1", "mtg_2");
        assertThat(facade.lastCommand.scope().documentIds()).containsExactly("doc_1");
        assertThat(facade.lastCommand.topN()).isEqualTo(5);
        assertThat(facade.lastCommand.includeStale()).isTrue();
    }

    @Test
    void queryDefaultsUserIdToAnonymousWhenHeaderAbsent() {
        StubFacade facade = new StubFacade();
        RagQueryController controller = newController(facade, permissiveLimiter());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.query(
            new RagQueryRequest("q", null, null, null),
            "req_03", "trace_03", null, null
        );

        assertThat(facade.lastCommand.userId()).isEqualTo("anonymous");
    }

    @Test
    void queryRejectsBlankQuestionAtAdapter() {
        RagQueryController controller = newController(new StubFacade(), permissiveLimiter());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.query(
            new RagQueryRequest("   ", null, null, null),
            "req_04", "trace_04", null, "user_01"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryRejectsAfterBurstCapacityExceeded() {
        StubFacade facade = new StubFacade();
        // burst capacity 2, very low rpm so refill is negligible inside the test window.
        RagRateLimiter limiter = new RagRateLimiter(/*rpm=*/ 1, /*burst=*/ 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeetingApiMetrics metrics = new MeetingApiMetrics(registry);
        RagQueryController controller = new RagQueryController(facade, limiter, metrics);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.query(new RagQueryRequest("q", null, null, null),
            "req_01", "trace_01", null, "user_01");
        controller.query(new RagQueryRequest("q", null, null, null),
            "req_02", "trace_02", null, "user_01");

        // 3rd request inside the burst window exceeds capacity.
        assertThatThrownBy(() -> controller.query(
            new RagQueryRequest("q", null, null, null),
            "req_03", "trace_03", null, "user_01"
        ))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException ae = (ApplicationException) ex;
                assertThat(ae.errorCode()).isEqualTo(ErrorCode.RAG_RATE_LIMITED);
                assertThat(ae.httpStatus()).isEqualTo(429);
                assertThat(ae.retryable()).isTrue();
            });
        assertThat(registry.counter("meeting.api.rag.rate_limit_blocks", "key", "tenant_user").count())
            .isEqualTo(1.0);
    }

    private static RagQueryController newController(RagQueryFacade facade, RagRateLimiter limiter) {
        return new RagQueryController(facade, limiter, new MeetingApiMetrics(new SimpleMeterRegistry()));
    }

    private static RagRateLimiter permissiveLimiter() {
        return new RagRateLimiter(6_000, 1_000);
    }

    private static final class StubFacade implements RagQueryFacade {
        RagQueryCommand lastCommand;

        @Override
        public RagAnswerDTO query(RagQueryCommand command) {
            this.lastCommand = command;
            return new RagAnswerDTO(
                "answer body",
                List.of(new MeetingSegmentCitationDTO(
                    "ck1", "mtg_1", "Stand-up", "seg_1", "Alice",
                    0L, 10_000L, "content"
                )),
                RagAnswerCoverage.TRANSCRIPT_ONLY,
                "llmlog_stub"
            );
        }
    }
}
