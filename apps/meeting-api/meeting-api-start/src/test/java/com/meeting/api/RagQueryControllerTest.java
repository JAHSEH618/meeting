package com.meeting.api;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.meeting.TenantContextMissingException;
import com.meeting.api.adapter.rag.RagQueryController;
import com.meeting.api.adapter.rag.RagQueryRequest;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.enums.RagAnswerCoverage;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.rag.MeetingSegmentCitationDTO;
import com.meeting.api.client.rag.RagAnswerDTO;
import com.meeting.api.client.rag.RagQueryCommand;
import com.meeting.api.client.rag.RagQueryFacade;
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
        RagQueryController controller = new RagQueryController(new StubFacade());
        assertThatThrownBy(() -> controller.query(
            new RagQueryRequest("q", null, null, null),
            "req_01", "trace_01", null, "user_01", null
        )).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void queryAppliesDefaultsAndDelegatesToFacade() {
        StubFacade facade = new StubFacade();
        RagQueryController controller = new RagQueryController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ResponseEntity<ApiResponse<RagAnswerDTO>> response = controller.query(
            new RagQueryRequest("How did we decide on roadmap?", null, null, null),
            "req_01", "trace_01", "idem_01", "user_01", null
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().answer()).isEqualTo("answer body");

        RagQueryCommand cmd = facade.lastCommand;
        assertThat(cmd.tenantId()).isEqualTo("tenant_01");
        assertThat(cmd.userId()).isEqualTo("user_01");
        assertThat(cmd.clearance()).isEqualTo(SecurityLevel.INTERNAL);
        assertThat(cmd.topN()).isEqualTo(8);
        assertThat(cmd.includeStale()).isFalse();
        assertThat(cmd.scope().isEmpty()).isTrue();
        assertThat(cmd.requestId()).isEqualTo("req_01");
        assertThat(cmd.traceId()).isEqualTo("trace_01");
    }

    @Test
    void queryParsesScopeAndClearanceHeader() {
        StubFacade facade = new StubFacade();
        RagQueryController controller = new RagQueryController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.query(
            new RagQueryRequest(
                "q",
                new RagQueryRequest.Scope(List.of("mtg_1", "mtg_2"), List.of("doc_1")),
                5, true
            ),
            "req_02", "trace_02", "idem_02", "user_42", "confidential"
        );

        assertThat(facade.lastCommand.userId()).isEqualTo("user_42");
        assertThat(facade.lastCommand.clearance()).isEqualTo(SecurityLevel.CONFIDENTIAL);
        assertThat(facade.lastCommand.scope().meetingIds()).containsExactly("mtg_1", "mtg_2");
        assertThat(facade.lastCommand.scope().documentIds()).containsExactly("doc_1");
        assertThat(facade.lastCommand.topN()).isEqualTo(5);
        assertThat(facade.lastCommand.includeStale()).isTrue();
    }

    @Test
    void queryDefaultsUserIdToAnonymousWhenHeaderAbsent() {
        StubFacade facade = new StubFacade();
        RagQueryController controller = new RagQueryController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.query(
            new RagQueryRequest("q", null, null, null),
            "req_03", "trace_03", null, null, null
        );

        assertThat(facade.lastCommand.userId()).isEqualTo("anonymous");
    }

    @Test
    void queryRejectsBlankQuestionAtAdapter() {
        RagQueryController controller = new RagQueryController(new StubFacade());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.query(
            new RagQueryRequest("   ", null, null, null),
            "req_04", "trace_04", null, "user_01", null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryRejectsInvalidClearanceHeader() {
        RagQueryController controller = new RagQueryController(new StubFacade());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.query(
            new RagQueryRequest("q", null, null, null),
            "req_05", "trace_05", null, "user_01", "OMEGA"
        )).isInstanceOf(IllegalArgumentException.class);
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
