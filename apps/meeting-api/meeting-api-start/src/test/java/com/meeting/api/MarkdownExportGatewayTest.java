package com.meeting.api;

import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.enums.SecurityLevel;
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
import com.meeting.api.infrastructure.gateway.export.MarkdownExportGateway;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownExportGatewayTest {

    private final MarkdownExportGateway gateway = new MarkdownExportGateway();

    @Test
    void rendersAllSectionsWithSpeakersAndTimestamps() {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(ExportRenderOptions.defaults(), null);

        ExportGateway.RenderedFile out = gateway.render(job, snapshot);
        String markdown = new String(out.bytes(), StandardCharsets.UTF_8);

        assertThat(markdown).contains("# Sample Meeting");
        assertThat(markdown).contains("## 与会人");
        assertThat(markdown).contains("张三");
        assertThat(markdown).contains("## 转录");
        assertThat(markdown).contains("[1:23] 张三:");           // formatted timestamp
        assertThat(markdown).contains("Hello world");
        assertThat(markdown).contains("## 纪要");
        assertThat(markdown).contains("Action items reviewed");
        assertThat(markdown).contains("## 待办");
        assertThat(markdown).contains("- [ ] Follow up");
        assertThat(markdown).contains("(责任人: 张三, 截止: 2026-06-01)");
        assertThat(markdown).contains("## 决策");
        assertThat(markdown).contains("Approve roadmap ✅");
        assertThat(markdown).contains("## 风险");
        assertThat(markdown).contains("**HIGH:** Supply chain");
        assertThat(markdown).doesNotContain("<!-- watermark");
    }

    @Test
    void appendsWatermarkCommentWhenSet() {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(
            ExportRenderOptions.defaults(), "INTERNAL · tenant_test_01"
        );

        ExportGateway.RenderedFile out = gateway.render(job, snapshot);
        String markdown = new String(out.bytes(), StandardCharsets.UTF_8);

        assertThat(markdown).endsWith("<!-- watermark: INTERNAL · tenant_test_01 -->\n");
    }

    @Test
    void elidesSectionsByRenderOptions() {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(
            new ExportRenderOptions(
                /* transcript */ false,
                /* minutes */    false,
                /* items */      false,
                /* speakers */   false
            ),
            null
        );

        String markdown = new String(
            gateway.render(job, snapshot).bytes(), StandardCharsets.UTF_8
        );
        assertThat(markdown).contains("# Sample Meeting");
        assertThat(markdown).doesNotContain("## 与会人");
        assertThat(markdown).doesNotContain("## 转录");
        assertThat(markdown).doesNotContain("## 纪要");
        assertThat(markdown).doesNotContain("## 待办");
        assertThat(markdown).doesNotContain("## 决策");
        assertThat(markdown).doesNotContain("## 风险");
    }

    @Test
    void sha256IsStableForSameInput() {
        MeetingSnapshot snapshot = sampleSnapshot();
        ExportJob job = jobWithOpts(ExportRenderOptions.defaults(), null);

        ExportGateway.RenderedFile a = gateway.render(job, snapshot);
        ExportGateway.RenderedFile b = gateway.render(job, snapshot);
        assertThat(a.sha256()).isEqualTo(b.sha256());
        assertThat(a.sha256()).startsWith("sha256:");
        assertThat(a.bytes()).isEqualTo(b.bytes());
        assertThat(a.sizeBytes()).isEqualTo(a.bytes().length);
    }

    @Test
    void supportsMarkdownFormat() {
        assertThat(gateway.supportedFormat()).isEqualTo(ExportFormat.MARKDOWN);
    }

    @Test
    void handlesEmptyMeetingSnapshotGracefully() {
        MeetingSnapshot empty = new MeetingSnapshot(
            "mtg_empty", "Empty meeting", SecurityLevel.INTERNAL, "zh",
            null, 0, null,
            List.of(), null, List.of(), List.of(), List.of(), List.of()
        );
        ExportJob job = jobWithOpts(ExportRenderOptions.defaults(), null);

        String markdown = new String(
            gateway.render(job, empty).bytes(), StandardCharsets.UTF_8
        );
        assertThat(markdown).contains("# Empty meeting");
        assertThat(markdown).doesNotContain("## 与会人");        // empty list elided
        assertThat(markdown).doesNotContain("## 转录");
        assertThat(markdown).doesNotContain("## 纪要");
    }

    private static MeetingSnapshot sampleSnapshot() {
        return new MeetingSnapshot(
            "mtg_sample", "Sample Meeting",
            SecurityLevel.INTERNAL, "zh",
            /* duration */ 3725L,
            /* transcriptVersion */ 3,
            /* minutesVersion */ 2,
            List.of(
                new TranscriptSegmentRow("seg_01", 0, 0L, 5000L,
                    "SPEAKER_00", "张三", "Hello world"),
                new TranscriptSegmentRow("seg_02", 1, 83000L, 90000L,
                    "SPEAKER_00", "张三", "Action items reviewed")
            ),
            new MinutesRow(2, "Meeting Notes",
                "## Highlights\n- Action items reviewed\n- Roadmap approved"),
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
            .id("exp_test_md_01")
            .tenantId("tenant_test_01")
            .meetingId("mtg_sample")
            .exportType(ExportType.MEETING)
            .format(ExportFormat.MARKDOWN)
            .dataBoundaryMode(ExportDataBoundaryMode.FULL)
            .inputTranscriptVersion(3)
            .inputMinutesVersion(2)
            .snapshotManifestId("mfst_test_01")
            .watermarkText(watermark)
            .renderOptions(opts)
            .createdBy("user_test_01")
            .createdAt(OffsetDateTime.parse("2026-05-18T02:00:00Z"))
            .status(ExportStatus.QUEUED)
            .build();
    }
}
