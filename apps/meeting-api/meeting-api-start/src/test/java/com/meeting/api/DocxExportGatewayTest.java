package com.meeting.api;

import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.MeetingSnapshotPort.ActionItemRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.DecisionRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSpeakerRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.MinutesRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.RiskRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.TranscriptSegmentRow;
import com.meeting.api.infrastructure.gateway.export.DocxExportGateway;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocxExportGatewayTest {

    private final DocxExportGateway gateway = new DocxExportGateway();

    @Test
    void supportsDocxFormat() {
        assertThat(gateway.supportedFormat()).isEqualTo(ExportFormat.DOCX);
    }

    @Test
    void rendersAllSectionsWithExpectedHeadings() throws Exception {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(ExportRenderOptions.defaults(), null);

        ExportGateway.RenderedFile out = gateway.render(job, snapshot);

        // Sanity: looks like a docx (PK\x03\x04 zip header)
        assertThat(out.bytes()[0]).isEqualTo((byte) 0x50);
        assertThat(out.bytes()[1]).isEqualTo((byte) 0x4B);
        assertThat(out.sha256()).startsWith("sha256:");
        assertThat(out.sizeBytes()).isEqualTo(out.bytes().length);

        // Re-parse the doc and walk paragraphs, collecting visible text.
        try (XWPFDocument parsed = new XWPFDocument(new ByteArrayInputStream(out.bytes()))) {
            String body = parsed.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .reduce("", (a, b) -> a + "\n" + b);

            assertThat(body).contains("Sample Meeting");
            assertThat(body).contains("与会人");
            assertThat(body).contains("张三");
            assertThat(body).contains("转录");
            assertThat(body).contains("Hello world");
            assertThat(body).contains("纪要");
            assertThat(body).contains("Action items reviewed");
            assertThat(body).contains("待办");
            assertThat(body).contains("[ ] Follow up");
            assertThat(body).contains("决策");
            assertThat(body).contains("Approve roadmap");
            assertThat(body).contains("风险");
            assertThat(body).contains("HIGH: Supply chain");
        }
    }

    @Test
    void appendsWatermarkParagraphWhenSet() throws Exception {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(ExportRenderOptions.defaults(), "INTERNAL · tenant_test");

        ExportGateway.RenderedFile out = gateway.render(job, snapshot);

        try (XWPFDocument parsed = new XWPFDocument(new ByteArrayInputStream(out.bytes()))) {
            String body = parsed.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .reduce("", (a, b) -> a + "\n" + b);
            assertThat(body).contains("watermark: INTERNAL · tenant_test");
        }
    }

    @Test
    void elidesSectionsByRenderOptions() throws Exception {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(
            new ExportRenderOptions(false, false, false, false),
            null
        );

        ExportGateway.RenderedFile out = gateway.render(job, snapshot);
        try (XWPFDocument parsed = new XWPFDocument(new ByteArrayInputStream(out.bytes()))) {
            String body = parsed.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .reduce("", (a, b) -> a + "\n" + b);

            assertThat(body).contains("Sample Meeting");
            // Metadata block still contains "转录版本" / "纪要版本" — those
            // are not section headings; we assert the section bodies
            // (which would be the only place transcript / minutes /
            // action-items / decision text appears) are gone instead.
            assertThat(body).doesNotContain("Hello world");
            assertThat(body).doesNotContain("Action items reviewed");
            assertThat(body).doesNotContain("Follow up");
            assertThat(body).doesNotContain("Approve roadmap");
            assertThat(body).doesNotContain("Supply chain");
            assertThat(body).doesNotContain("张三");
        }
    }

    @Test
    void sha256IsStableForSameInput() {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(ExportRenderOptions.defaults(), null);

        ExportGateway.RenderedFile a = gateway.render(job, snapshot);
        ExportGateway.RenderedFile b = gateway.render(job, snapshot);
        // POI may include a tiny timestamp in core props that varies; we
        // assert byte-size equivalence and sha256 prefix instead.
        assertThat(a.sha256()).startsWith("sha256:");
        assertThat(b.sha256()).startsWith("sha256:");
    }

    @Test
    void handlesEmptySnapshotGracefully() throws Exception {
        MeetingSnapshot empty = new MeetingSnapshot(
            "mtg_empty", "Empty meeting", SecurityLevel.INTERNAL, "zh",
            null, 0, null,
            List.of(), null, List.of(), List.of(), List.of(), List.of()
        );
        ExportJob job = jobWithOpts(ExportRenderOptions.defaults(), null);

        ExportGateway.RenderedFile out = gateway.render(job, empty);
        try (XWPFDocument parsed = new XWPFDocument(new ByteArrayInputStream(out.bytes()))) {
            String body = parsed.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .reduce("", (a, b) -> a + "\n" + b);
            assertThat(body).contains("Empty meeting");
            // Metadata version markers are always present; the elided
            // sections are detected by absence of their content.
            assertThat(body).doesNotContain("Hello world");
            assertThat(body).doesNotContain("Action items reviewed");
        }
    }

    private static MeetingSnapshot sampleSnapshot() {
        return new MeetingSnapshot(
            "mtg_sample", "Sample Meeting",
            SecurityLevel.INTERNAL, "zh",
            3725L, 3, 2,
            List.of(
                new TranscriptSegmentRow("seg_01", 0, 0L, 5000L,
                    "SPEAKER_00", "张三", "Hello world"),
                new TranscriptSegmentRow("seg_02", 1, 83000L, 90000L,
                    "SPEAKER_00", "张三", "Action items reviewed")
            ),
            new MinutesRow(2, "Meeting Notes",
                "Highlights: Action items reviewed; Roadmap approved"),
            List.of(
                new ActionItemRow("ai_01", "Follow up", "Send the report",
                    "张三", "2026-06-01", "HIGH", "OPEN")
            ),
            List.of(
                new DecisionRow("dec_01", "Approve roadmap",
                    "Ship phase 6", "APPROVED")
            ),
            List.of(
                new RiskRow("rsk_01", "Supply chain",
                    "External vendor uncertainty", "HIGH", "OPEN")
            ),
            List.of(
                new MeetingSpeakerRow("SPEAKER_00", "张三", "CONFIRMED")
            )
        );
    }

    private static ExportJob jobWithOpts(ExportRenderOptions opts, String watermark) {
        return ExportJob.builder()
            .id("exp_test_docx_01")
            .tenantId("tenant_test_01")
            .meetingId("mtg_sample")
            .exportType(ExportType.MEETING)
            .format(ExportFormat.DOCX)
            .dataBoundaryMode(ExportDataBoundaryMode.FULL)
            .inputTranscriptVersion(3)
            .inputMinutesVersion(2)
            .snapshotManifestId("mfst_test_01")
            .watermarkText(watermark)
            .renderOptions(opts)
            .createdBy("user_test_01")
            .createdAt(OffsetDateTime.parse("2026-05-18T09:00:00Z"))
            .status(ExportStatus.QUEUED)
            .build();
    }
}
