package com.meeting.api;

import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import com.meeting.api.domain.export.MeetingSnapshotPort.MinutesRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.TranscriptSegmentRow;
import com.meeting.api.infrastructure.gateway.export.DocxExportGateway;
import com.meeting.api.infrastructure.gateway.export.PdfExportGateway;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfExportGatewayTest {

    @Test
    void supportsPdfFormat() {
        PdfExportGateway gw = new PdfExportGateway(new DocxExportGateway(), "soffice", 60L);
        assertThat(gw.supportedFormat()).isEqualTo(ExportFormat.PDF);
    }

    @Test
    void missingBinaryRaisesRenderFailed() {
        PdfExportGateway gw = new PdfExportGateway(
            new DocxExportGateway(),
            "/definitely/not/installed/soffice-" + System.nanoTime(),
            10L
        );
        ExportJob job = sampleJob();
        MeetingSnapshot snapshot = sampleSnapshot();

        assertThatThrownBy(() -> gw.render(job, snapshot))
            .isInstanceOf(ExportRuntimeException.class)
            .hasMessageContaining("LibreOffice")
            .extracting(ex -> ((ExportRuntimeException) ex).errorCode())
            .isEqualTo(ErrorCode.EXPORT_RENDER_FAILED);
    }

    private static MeetingSnapshot sampleSnapshot() {
        return new MeetingSnapshot(
            "mtg_pdf_sample", "Sample Meeting",
            SecurityLevel.INTERNAL, "zh",
            120L, 1, 1,
            List.of(new TranscriptSegmentRow(
                "seg_01", 0, 0L, 5000L,
                "SPEAKER_00", "张三", "Hello world"
            )),
            new MinutesRow(1, "Notes", "Approved"),
            List.of(), List.of(), List.of(), List.of()
        );
    }

    private static ExportJob sampleJob() {
        return ExportJob.builder()
            .id("exp_test_pdf_01")
            .tenantId("tenant_test_01")
            .meetingId("mtg_pdf_sample")
            .exportType(ExportType.MEETING)
            .format(ExportFormat.PDF)
            .dataBoundaryMode(ExportDataBoundaryMode.FULL)
            .inputTranscriptVersion(1)
            .inputMinutesVersion(1)
            .renderOptions(ExportRenderOptions.defaults())
            .createdBy("user_test_01")
            .createdAt(OffsetDateTime.parse("2026-05-18T09:00:00Z"))
            .status(ExportStatus.QUEUED)
            .build();
    }
}
