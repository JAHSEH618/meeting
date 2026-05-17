package com.meeting.api;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import com.meeting.api.infrastructure.gateway.export.ExportGatewayRegistry;
import com.meeting.api.infrastructure.gateway.export.MarkdownExportGateway;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportGatewayRegistryTest {

    @Test
    void routesByFormat() {
        ExportGatewayRegistry registry = new ExportGatewayRegistry(
            List.of(new MarkdownExportGateway())
        );
        assertThat(registry.gateway(ExportFormat.MARKDOWN))
            .isInstanceOf(MarkdownExportGateway.class);
    }

    @Test
    void throwsForUnregisteredFormat() {
        ExportGatewayRegistry registry = new ExportGatewayRegistry(List.of());
        assertThatThrownBy(() -> registry.gateway(ExportFormat.PDF))
            .isInstanceOf(ExportInputInvalidException.class)
            .matches(ex -> ((ExportInputInvalidException) ex).errorCode()
                == ErrorCode.EXPORT_FORMAT_UNSUPPORTED);
    }

    @Test
    void rejectsDuplicateRegistration() {
        ExportGateway dup = new ExportGateway() {
            @Override public ExportFormat supportedFormat() { return ExportFormat.MARKDOWN; }
            @Override public RenderedFile render(ExportJob j, MeetingSnapshot s) { return null; }
        };
        assertThatThrownBy(() -> new ExportGatewayRegistry(List.of(new MarkdownExportGateway(), dup)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Multiple ExportGateway");
    }
}
