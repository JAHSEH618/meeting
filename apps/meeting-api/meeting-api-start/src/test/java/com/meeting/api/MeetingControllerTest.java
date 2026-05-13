package com.meeting.api;

import com.meeting.api.adapter.meeting.MeetingController;
import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.meeting.TenantContextMissingException;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingControllerTest {

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void listFailsClosedWhenTenantContextIsMissing() {
        MeetingController controller = new MeetingController(new StubMeetingFacade());

        assertThatThrownBy(() -> controller.list("req_01", "trace_01"))
            .isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void listUsesTenantContextFromHolder() {
        StubMeetingFacade facade = new StubMeetingFacade();
        MeetingController controller = new MeetingController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<List<MeetingDTO>> response = controller.list("req_01", "trace_01");

        assertThat(facade.lastListTenantId).isEqualTo("tenant_01");
        assertThat(response.success()).isTrue();
        assertThat(response.data()).hasSize(1);
        assertThat(response.requestId()).isEqualTo("req_01");
        assertThat(response.traceId()).isEqualTo("trace_01");
    }

    @Test
    void getReturnsNotFoundWhenFacadeHasNoMeetingForTenant() {
        StubMeetingFacade facade = new StubMeetingFacade();
        facade.getResult = Optional.empty();
        MeetingController controller = new MeetingController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ResponseEntity<ApiResponse<MeetingDTO>> response = controller.get("req_01", "trace_01", "missing");

        assertThat(facade.lastGetTenantId).isEqualTo("tenant_01");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    private static final class StubMeetingFacade implements MeetingFacade {
        private final MeetingDTO meeting = new MeetingDTO(
            "m_01",
            "tenant_01",
            "Weekly",
            SecurityLevel.INTERNAL,
            MeetingStatus.CREATED,
            "zh",
            0,
            0,
            OffsetDateTime.parse("2026-01-01T00:00:00Z")
        );
        private String lastListTenantId;
        private String lastGetTenantId;
        private Optional<MeetingDTO> getResult = Optional.of(meeting);

        @Override
        public MeetingDTO create(CreateMeetingCommand command) {
            return meeting;
        }

        @Override
        public Optional<MeetingDTO> get(String tenantId, String meetingId) {
            lastGetTenantId = tenantId;
            return getResult;
        }

        @Override
        public List<MeetingDTO> list(String tenantId) {
            lastListTenantId = tenantId;
            return List.of(meeting);
        }
    }
}
