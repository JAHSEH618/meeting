package com.meeting.api;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.sse.ExportSseController;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.export.CreateExportCommand;
import com.meeting.api.client.export.ExportFacade;
import com.meeting.api.client.export.ExportJobDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportSseControllerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MeetingApiMetrics metrics = new MeetingApiMetrics(registry);

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void sendsSnapshotAndIncrementsMetrics() throws IOException {
        StubExportFacade facade = new StubExportFacade();
        facade.dto = sampleDto(ExportStatus.SUCCEEDED);
        ExportSseController controller = new ExportSseController(facade, metrics);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        SseEmitter emitter = controller.events("exp_01", null);

        assertThat(emitter).isNotNull();
        assertThat(facade.lastExportId).isEqualTo("exp_01");
        assertThat(facade.lastTenantId).isEqualTo("tenant_01");
        assertThat(registry.counter("meeting.api.sse.opened").count()).isEqualTo(1.0);
        assertThat(
            registry.counter("meeting.api.sse.events", "eventType", "EXPORT_STATUS_CHANGED").count()
        ).isEqualTo(1.0);
    }

    @Test
    void rejectsMissingExport() {
        StubExportFacade facade = new StubExportFacade();
        facade.dto = null;
        ExportSseController controller = new ExportSseController(facade, metrics);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.events("exp_missing", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exp_missing");
        assertThat(registry.counter("meeting.api.sse.opened").count()).isZero();
    }

    private static ExportJobDTO sampleDto(ExportStatus status) {
        return new ExportJobDTO(
            "exp_01",
            "m_01",
            status,
            ExportFormat.PDF,
            ExportDataBoundaryMode.FULL,
            3,
            1,
            "manifest_01",
            "Confidential",
            status == ExportStatus.SUCCEEDED ? "https://example/download" : null,
            status == ExportStatus.SUCCEEDED ? OffsetDateTime.parse("2026-05-20T10:00:00Z") : null,
            "abc123",
            12345L,
            false,
            false,
            null,
            OffsetDateTime.parse("2026-06-20T10:00:00Z"),
            OffsetDateTime.parse("2026-05-18T10:00:00Z"),
            status == ExportStatus.SUCCEEDED ? OffsetDateTime.parse("2026-05-18T10:01:00Z") : null
        );
    }

    private static final class StubExportFacade implements ExportFacade {
        ExportJobDTO dto;
        String lastExportId;
        String lastTenantId;

        @Override
        public ExportJobDTO create(CreateExportCommand command) {
            return dto;
        }

        @Override
        public Optional<ExportJobDTO> get(String tenantId, String exportId) {
            lastTenantId = tenantId;
            lastExportId = exportId;
            return Optional.ofNullable(dto);
        }

        @Override
        public PageResult<ExportJobDTO> listByMeeting(
            String tenantId, String meetingId, String cursor, int limit
        ) {
            return new PageResult<>(java.util.List.of(), null);
        }

        @Override
        public void cancel(String tenantId, String exportId, String userId) {
            // no-op
        }

        @Override
        public void revokeLink(String tenantId, String exportId, String userId) {
            // no-op
        }
    }
}
