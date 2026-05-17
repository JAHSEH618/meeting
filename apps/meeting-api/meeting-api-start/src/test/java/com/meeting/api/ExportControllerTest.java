package com.meeting.api;

import com.meeting.api.adapter.export.ExportController;
import com.meeting.api.adapter.export.ExportController.CreateExportRequest;
import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.export.CreateExportCommand;
import com.meeting.api.client.export.ExportFacade;
import com.meeting.api.client.export.ExportJobDTO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportControllerTest {

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void createBuildsCommandWithDefaultRenderOptions() {
        StubFacade facade = new StubFacade();
        ExportController controller = new ExportController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ResponseEntity<ApiResponse<ExportJobDTO>> response = controller.create(
            "mtg_01",
            new CreateExportRequest(
                ExportFormat.PDF, 3, 2,
                /* includeTranscript */ null,
                /* includeMinutes */    null,
                /* includeItems */      null,
                /* includeSpeakers */   null,
                /* watermark */         null
            ),
            "req_01", "trace_01", "idem_01", "user_01"
        );

        assertThat(response.getBody().success()).isTrue();
        CreateExportCommand cmd = facade.lastCreateCommand;
        assertThat(cmd.tenantId()).isEqualTo("tenant_01");
        assertThat(cmd.meetingId()).isEqualTo("mtg_01");
        assertThat(cmd.format()).isEqualTo(ExportFormat.PDF);
        assertThat(cmd.expectedTranscriptVersion()).isEqualTo(3);
        assertThat(cmd.expectedMinutesVersion()).isEqualTo(2);
        assertThat(cmd.createdBy()).isEqualTo("user_01");
        // missing flags default to true
        assertThat(cmd.renderOptions().includeTranscript()).isTrue();
        assertThat(cmd.renderOptions().includeMinutes()).isTrue();
        assertThat(cmd.renderOptions().includeItems()).isTrue();
        assertThat(cmd.renderOptions().includeSpeakers()).isTrue();
    }

    @Test
    void createPassesExplicitRenderOptionsAndWatermark() {
        StubFacade facade = new StubFacade();
        ExportController controller = new ExportController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.create(
            "mtg_01",
            new CreateExportRequest(
                ExportFormat.MARKDOWN, 0, null,
                true, false, true, false, "WM"
            ),
            "req_02", "trace_02", "idem_02", "user_99"
        );

        CreateExportCommand cmd = facade.lastCreateCommand;
        assertThat(cmd.renderOptions().includeMinutes()).isFalse();
        assertThat(cmd.renderOptions().includeSpeakers()).isFalse();
        assertThat(cmd.watermarkText()).isEqualTo("WM");
        assertThat(cmd.createdBy()).isEqualTo("user_99");
    }

    @Test
    void createRejectsNullBody() {
        ExportController controller = new ExportController(new StubFacade());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");
        assertThatThrownBy(() -> controller.create(
            "mtg_01", null, "req_01", "trace_01", "idem_01", "user_01"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("request body");
    }

    @Test
    void getReturns404StyleErrorWhenMissing() {
        ExportController controller = new ExportController(new StubFacade());
        TenantContextHolder.set("tenant_01", "user_01", "req_01");
        assertThatThrownBy(() -> controller.get("exp_unknown", "req_01", "trace_01"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("export not found");
    }

    @Test
    void cancelDelegatesWithUserId() {
        StubFacade facade = new StubFacade();
        ExportController controller = new ExportController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.cancel("exp_01", "req_01", "trace_01", "idem_01", "user_42");
        assertThat(facade.cancelTenantId).isEqualTo("tenant_01");
        assertThat(facade.cancelExportId).isEqualTo("exp_01");
        assertThat(facade.cancelUserId).isEqualTo("user_42");
    }

    @Test
    void cancelDefaultsAnonymousUserWhenHeaderAbsent() {
        StubFacade facade = new StubFacade();
        ExportController controller = new ExportController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.cancel("exp_01", "req_01", "trace_01", null, null);
        assertThat(facade.cancelUserId).isEqualTo("anonymous");
    }

    @Test
    void revokeLinkDelegates() {
        StubFacade facade = new StubFacade();
        ExportController controller = new ExportController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.revokeLink("exp_01", "req_01", "trace_01", "idem_01", "user_42");
        assertThat(facade.revokeTenantId).isEqualTo("tenant_01");
        assertThat(facade.revokeExportId).isEqualTo("exp_01");
        assertThat(facade.revokeUserId).isEqualTo("user_42");
    }

    @Test
    void listForwardsCursorAndLimit() {
        StubFacade facade = new StubFacade();
        ExportController controller = new ExportController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.list("mtg_01", "abc|exp_99", 5, "req_01", "trace_01");
        assertThat(facade.listCursor).isEqualTo("abc|exp_99");
        assertThat(facade.listLimit).isEqualTo(5);
    }

    private static class StubFacade implements ExportFacade {
        CreateExportCommand lastCreateCommand;
        String listCursor;
        int listLimit;
        String cancelTenantId, cancelExportId, cancelUserId;
        String revokeTenantId, revokeExportId, revokeUserId;

        @Override
        public ExportJobDTO create(CreateExportCommand command) {
            this.lastCreateCommand = command;
            return new ExportJobDTO(
                "exp_test", command.meetingId(), ExportStatus.QUEUED, command.format(),
                ExportDataBoundaryMode.FULL, command.expectedTranscriptVersion(),
                command.expectedMinutesVersion(), "mfst_01", command.watermarkText(),
                null, null, null, null, false, false, null,
                OffsetDateTime.parse("2026-05-19T02:00:00Z"),
                OffsetDateTime.parse("2026-05-18T02:00:00Z"),
                null
            );
        }

        @Override
        public Optional<ExportJobDTO> get(String tenantId, String exportId) {
            return Optional.empty();
        }

        @Override
        public PageResult<ExportJobDTO> listByMeeting(
            String tenantId, String meetingId, String cursor, int limit
        ) {
            this.listCursor = cursor;
            this.listLimit = limit;
            return new PageResult<>(List.of(), new PageResult.PageInfo(null, false, limit));
        }

        @Override
        public void cancel(String tenantId, String exportId, String userId) {
            this.cancelTenantId = tenantId;
            this.cancelExportId = exportId;
            this.cancelUserId = userId;
        }

        @Override
        public void revokeLink(String tenantId, String exportId, String userId) {
            this.revokeTenantId = tenantId;
            this.revokeExportId = exportId;
            this.revokeUserId = userId;
        }
    }
}
