package com.meeting.api.infrastructure.gateway.export;

import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.MeetingSnapshotPort.ActionItemRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.DecisionRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSpeakerRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.RiskRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.TranscriptSegmentRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * Pure-Java Markdown renderer for {@link ExportFormat#MARKDOWN}.
 *
 * <p>Layout:
 * <pre>
 *   # 标题
 *
 *   - 语言 / 时长 ...
 *
 *   ## 与会人
 *   - 张三 (已确认)
 *
 *   ## 转录
 *   **[00:01:23] 张三:** 大家好...
 *
 *   ## 纪要
 *   <minutes markdown body>
 *
 *   ## 待办
 *   - [ ] xxx (责任人: 张三, 截止: 2026-06-01)
 *
 *   ## 决策
 *   - xxx ✅
 *
 *   ## 风险
 *   - **高:** xxx
 *
 *   <!-- watermark: ... -->
 * </pre>
 *
 * <p>{@code includeXxx} flags from {@link ExportRenderOptions} elide
 * the corresponding section entirely (no empty headers).
 */
@Component
public class MarkdownExportGateway implements ExportGateway {

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.MARKDOWN;
    }

    @Override
    public RenderedFile render(ExportJob job, MeetingSnapshot snapshot) {
        ExportRenderOptions opts = job.renderOptions();
        StringBuilder sb = new StringBuilder();
        renderHeader(sb, snapshot);
        if (opts.includeSpeakers()) {
            renderSpeakers(sb, snapshot.speakers());
        }
        if (opts.includeTranscript()) {
            renderTranscript(sb, snapshot.segments());
        }
        if (opts.includeMinutes() && snapshot.minutes() != null) {
            renderMinutes(sb, snapshot.minutes().title(), snapshot.minutes().markdown());
        }
        if (opts.includeItems()) {
            renderActionItems(sb, snapshot.actionItems());
            renderDecisions(sb, snapshot.decisions());
            renderRisks(sb, snapshot.risks());
        }
        if (job.watermarkText() != null && !job.watermarkText().isBlank()) {
            sb.append("\n<!-- watermark: ").append(job.watermarkText()).append(" -->\n");
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new RenderedFile(bytes, sha256Hex(bytes), bytes.length);
    }

    private void renderHeader(StringBuilder sb, MeetingSnapshot s) {
        sb.append("# ").append(s.title() == null ? "(untitled)" : s.title()).append("\n\n");
        sb.append("- 语言: ").append(s.language() == null ? "zh" : s.language()).append("\n");
        if (s.durationSeconds() != null) {
            sb.append("- 时长: ").append(formatDuration(s.durationSeconds())).append("\n");
        }
        sb.append("- 转录版本: ").append(s.transcriptVersion());
        if (s.minutesVersion() != null) {
            sb.append(" / 纪要版本: ").append(s.minutesVersion());
        }
        sb.append("\n\n");
    }

    private void renderSpeakers(StringBuilder sb, java.util.List<MeetingSpeakerRow> speakers) {
        if (speakers == null || speakers.isEmpty()) return;
        sb.append("## 与会人\n\n");
        for (MeetingSpeakerRow sp : speakers) {
            sb.append("- ").append(sp.displayName());
            if (sp.verificationStatus() != null && !"CANDIDATE".equals(sp.verificationStatus())) {
                sb.append(" *(").append(sp.verificationStatus()).append(")*");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void renderTranscript(StringBuilder sb, java.util.List<TranscriptSegmentRow> segs) {
        if (segs == null || segs.isEmpty()) return;
        sb.append("## 转录\n\n");
        for (TranscriptSegmentRow seg : segs) {
            String speaker = seg.speakerName() == null || seg.speakerName().isBlank()
                ? seg.speakerLabel()
                : seg.speakerName();
            sb.append("**[").append(formatTimestamp(seg.startMs())).append("] ")
              .append(speaker).append(":** ")
              .append(seg.text() == null ? "" : seg.text().strip())
              .append("\n\n");
        }
    }

    private void renderMinutes(StringBuilder sb, String title, String markdown) {
        sb.append("## 纪要");
        if (title != null && !title.isBlank()) {
            sb.append(" · ").append(title);
        }
        sb.append("\n\n");
        sb.append(markdown == null ? "(empty)" : markdown.strip()).append("\n\n");
    }

    private void renderActionItems(StringBuilder sb, java.util.List<ActionItemRow> items) {
        if (items == null || items.isEmpty()) return;
        sb.append("## 待办\n\n");
        for (ActionItemRow item : items) {
            String box = "COMPLETED".equals(item.status()) ? "[x]" : "[ ]";
            sb.append("- ").append(box).append(" ").append(item.title());
            StringBuilder meta = new StringBuilder();
            if (item.ownerName() != null && !item.ownerName().isBlank()) {
                meta.append("责任人: ").append(item.ownerName());
            }
            if (item.deadline() != null && !item.deadline().isBlank()) {
                if (meta.length() > 0) meta.append(", ");
                meta.append("截止: ").append(item.deadline());
            }
            if (meta.length() > 0) {
                sb.append(" (").append(meta).append(")");
            }
            if (item.description() != null && !item.description().isBlank()) {
                sb.append("\n  ").append(item.description().strip());
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void renderDecisions(StringBuilder sb, java.util.List<DecisionRow> decisions) {
        if (decisions == null || decisions.isEmpty()) return;
        sb.append("## 决策\n\n");
        for (DecisionRow d : decisions) {
            String marker = "APPROVED".equals(d.status()) || "IMPLEMENTED".equals(d.status())
                ? " ✅" : "";
            sb.append("- ").append(d.title()).append(marker);
            if (d.description() != null && !d.description().isBlank()) {
                sb.append("\n  ").append(d.description().strip());
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void renderRisks(StringBuilder sb, java.util.List<RiskRow> risks) {
        if (risks == null || risks.isEmpty()) return;
        sb.append("## 风险\n\n");
        for (RiskRow r : risks) {
            sb.append("- **").append(r.severity() == null ? "MEDIUM" : r.severity())
              .append(":** ").append(r.title());
            if (r.description() != null && !r.description().isBlank()) {
                sb.append("\n  ").append(r.description().strip());
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private static String formatTimestamp(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0
            ? String.format("%d:%02d:%02d", h, m, s)
            : String.format("%d:%02d", m, s);
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0
            ? String.format("%dh %02dm %02ds", h, m, s)
            : String.format("%dm %02ds", m, s);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(2 + digest.length * 2);
            hex.append("sha256:");
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
