package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.adapter.meeting.MeetingController;
import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.meeting.TenantContextMissingException;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingResult;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import com.meeting.api.client.meeting.UpdateMeetingCommand;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

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

    @Test
    void deleteForwardsCommandWithTenantContextAndReasonBody() {
        StubMeetingFacade facade = new StubMeetingFacade();
        MeetingController controller = new MeetingController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<DeleteMeetingResult> response = controller.delete(
            "req_01", "trace_01", "idem_01", "m_01",
            new MeetingController.DeleteMeetingRequest("policy_violation", Boolean.TRUE, 3)
        );

        assertThat(response.success()).isTrue();
        DeleteMeetingCommand captured = facade.lastDeleteCommand;
        assertThat(captured.tenantId()).isEqualTo("tenant_01");
        assertThat(captured.meetingId()).isEqualTo("m_01");
        assertThat(captured.actorUserId()).isEqualTo("user_01");
        assertThat(captured.requestId()).isEqualTo("req_01");
        assertThat(captured.reason()).isEqualTo("policy_violation");
        assertThat(captured.expectedTranscriptVersion()).isEqualTo(3);
        assertThat(captured.legalHoldAcknowledged()).isTrue();
        assertThat(response.data().status()).isEqualTo(MeetingStatus.DELETED);
    }

    @Test
    void deleteAcceptsEmptyBodyAndDefaultsOptionalFields() {
        StubMeetingFacade facade = new StubMeetingFacade();
        MeetingController controller = new MeetingController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.delete("req_01", "trace_01", "idem_01", "m_01", null);

        DeleteMeetingCommand captured = facade.lastDeleteCommand;
        assertThat(captured.reason()).isNull();
        assertThat(captured.expectedTranscriptVersion()).isNull();
        assertThat(captured.legalHoldAcknowledged()).isFalse();
    }

    @Test
    void updateForwardsParticipantsWithTenantContextAndVersion() {
        StubMeetingFacade facade = new StubMeetingFacade();
        MeetingController controller = new MeetingController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<MeetingDTO> response = controller.update(
            "req_01",
            "trace_01",
            "idem_01",
            "m_01",
            json("""
                {
                  "scheduledStartAt": "2026-01-03T11:30:00Z",
                  "participants": [
                    {"personId": "p_01", "displayName": "李四", "role": "PARTICIPANT"}
                  ],
                  "expectedVersion": 3
                }
                """)
        );

        assertThat(response.success()).isTrue();
        UpdateMeetingCommand captured = facade.lastUpdateCommand;
        assertThat(captured.tenantId()).isEqualTo("tenant_01");
        assertThat(captured.meetingId()).isEqualTo("m_01");
        assertThat(captured.actorUserId()).isEqualTo("user_01");
        assertThat(captured.requestId()).isEqualTo("req_01");
        assertThat(captured.scheduledStartAt()).isEqualTo(OffsetDateTime.parse("2026-01-03T11:30:00Z"));
        assertThat(captured.scheduledStartAtProvided()).isTrue();
        assertThat(captured.expectedVersion()).isEqualTo(3);
        assertThat(captured.participants())
            .extracting(CreateMeetingCommand.ParticipantCommand::personId)
            .containsExactly("p_01");
    }

    @Test
    void updateDistinguishesMissingAndExplicitNullScheduledStart() {
        StubMeetingFacade facade = new StubMeetingFacade();
        MeetingController controller = new MeetingController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.update("req_01", "trace_01", "idem_01", "m_01", json("{}"));
        UpdateMeetingCommand missing = facade.lastUpdateCommand;
        assertThat(missing.scheduledStartAt()).isNull();
        assertThat(missing.scheduledStartAtProvided()).isFalse();

        controller.update("req_02", "trace_02", "idem_02", "m_01", json("""
            {"scheduledStartAt": null}
            """));
        UpdateMeetingCommand explicitNull = facade.lastUpdateCommand;
        assertThat(explicitNull.scheduledStartAt()).isNull();
        assertThat(explicitNull.scheduledStartAtProvided()).isTrue();
    }

    @Test
    void updateRejectsInvalidScheduledStartAtAsValidationError() {
        MeetingController controller = new MeetingController(new StubMeetingFacade());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.update(
            "req_01",
            "trace_01",
            "idem_01",
            "m_01",
            json("""
                {"scheduledStartAt": "not-a-date"}
                """)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scheduledStartAt");
    }

    @Test
    void deletePropagatesApplicationException() {
        StubMeetingFacade facade = new StubMeetingFacade();
        facade.deleteException = new ApplicationException(
            ErrorCode.LEGAL_HOLD_BLOCKED, 423, "under hold", false
        );
        MeetingController controller = new MeetingController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.delete(
            "req_01", "trace_01", "idem_01", "m_01", null
        ))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).errorCode())
                .isEqualTo(ErrorCode.LEGAL_HOLD_BLOCKED));
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
        private UpdateMeetingCommand lastUpdateCommand;
        private DeleteMeetingCommand lastDeleteCommand;
        private Optional<MeetingDTO> getResult = Optional.of(meeting);
        private ApplicationException deleteException;

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

        @Override
        public MeetingDTO update(UpdateMeetingCommand command) {
            lastUpdateCommand = command;
            return meeting;
        }

        @Override
        public DeleteMeetingResult delete(DeleteMeetingCommand command) {
            lastDeleteCommand = command;
            if (deleteException != null) {
                throw deleteException;
            }
            return new DeleteMeetingResult(
                command.meetingId(),
                MeetingStatus.DELETED,
                OffsetDateTime.parse("2026-05-20T10:00:00Z")
            );
        }
    }

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
