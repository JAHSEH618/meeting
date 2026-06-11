package com.meeting.api.infrastructure.gateway.export;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.meeting.api.domain.export.MeetingSnapshotPort.ActionItemRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.DecisionRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSpeakerRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.RiskRow;
import com.meeting.api.domain.export.MeetingSnapshotPort.TranscriptSegmentRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumbering;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.springframework.stereotype.Component;

/**
 * DOCX implementation of {@link ExportGateway} backed by Apache POI's
 * XWPF API. Renders the same logical sections as
 * {@code MarkdownExportGateway}, but emits a Word document with
 * heading styles, bulleted lists, and a footer-like watermark line.
 */
@Component
public class DocxExportGateway implements ExportGateway {

    private static final BigInteger NUM_ID = BigInteger.valueOf(1);

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.DOCX;
    }

    @Override
    public RenderedFile render(ExportJob job, MeetingSnapshot snapshot) {
        ExportRenderOptions opts = job.renderOptions();
        try (XWPFDocument doc = new XWPFDocument()) {
            ensureBulletNumbering(doc);
            renderTitle(doc, snapshot);
            renderMetadata(doc, snapshot);
            if (opts.includeSpeakers()) {
                renderSpeakers(doc, snapshot.speakers());
            }
            if (opts.includeTranscript()) {
                renderTranscript(doc, snapshot.segments());
            }
            if (opts.includeMinutes() && snapshot.minutes() != null) {
                renderMinutes(doc, snapshot.minutes().title(), snapshot.minutes().markdown());
            }
            if (opts.includeItems()) {
                renderActionItems(doc, snapshot.actionItems());
                renderDecisions(doc, snapshot.decisions());
                renderRisks(doc, snapshot.risks());
            }
            if (job.watermarkText() != null && !job.watermarkText().isBlank()) {
                renderWatermark(doc, job.watermarkText());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            byte[] bytes = out.toByteArray();
            return new RenderedFile(bytes, sha256Hex(bytes), bytes.length);
        } catch (IOException ex) {
            throw new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED, "DOCX render failed: " + ex.getMessage(), ex
            );
        }
    }

    private void renderTitle(XWPFDocument doc, MeetingSnapshot s) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading1");
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(20);
        r.setText(s.title() == null ? "(untitled)" : s.title());
    }

    private void renderMetadata(XWPFDocument doc, MeetingSnapshot s) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText("语言: " + (s.language() == null ? "zh" : s.language()));
        if (s.durationSeconds() != null) {
            r.addBreak();
            r.setText("时长: " + formatDuration(s.durationSeconds()));
        }
        r.addBreak();
        r.setText("转录版本: " + s.transcriptVersion()
            + (s.minutesVersion() != null ? " / 纪要版本: " + s.minutesVersion() : ""));
    }

    private void renderSpeakers(XWPFDocument doc, java.util.List<MeetingSpeakerRow> speakers) {
        if (speakers == null || speakers.isEmpty()) return;
        heading(doc, "与会人");
        for (MeetingSpeakerRow sp : speakers) {
            StringBuilder text = new StringBuilder(sp.displayName());
            if (sp.verificationStatus() != null && !"CANDIDATE".equals(sp.verificationStatus())) {
                text.append(" (").append(sp.verificationStatus()).append(")");
            }
            bullet(doc, text.toString());
        }
    }

    private void renderTranscript(XWPFDocument doc, java.util.List<TranscriptSegmentRow> segs) {
        if (segs == null || segs.isEmpty()) return;
        heading(doc, "转录");
        for (TranscriptSegmentRow seg : segs) {
            String speaker = seg.speakerName() == null || seg.speakerName().isBlank()
                ? seg.speakerLabel()
                : seg.speakerName();
            XWPFParagraph p = doc.createParagraph();
            XWPFRun bold = p.createRun();
            bold.setBold(true);
            bold.setText("[" + formatTimestamp(seg.startMs()) + "] " + speaker + ": ");
            XWPFRun body = p.createRun();
            body.setText(seg.text() == null ? "" : seg.text().strip());
        }
    }

    private void renderMinutes(XWPFDocument doc, String title, String markdown) {
        StringBuilder header = new StringBuilder("纪要");
        if (title != null && !title.isBlank()) {
            header.append(" · ").append(title);
        }
        heading(doc, header.toString());
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(markdown == null ? "(empty)" : markdown.strip());
    }

    private void renderActionItems(XWPFDocument doc, java.util.List<ActionItemRow> items) {
        if (items == null || items.isEmpty()) return;
        heading(doc, "待办");
        for (ActionItemRow item : items) {
            String box = "COMPLETED".equals(item.status()) ? "[x] " : "[ ] ";
            StringBuilder text = new StringBuilder(box).append(item.title());
            StringBuilder meta = new StringBuilder();
            if (item.ownerName() != null && !item.ownerName().isBlank()) {
                meta.append("责任人: ").append(item.ownerName());
            }
            if (item.deadline() != null && !item.deadline().isBlank()) {
                if (meta.length() > 0) meta.append(", ");
                meta.append("截止: ").append(item.deadline());
            }
            if (meta.length() > 0) {
                text.append(" (").append(meta).append(")");
            }
            bullet(doc, text.toString());
            if (item.description() != null && !item.description().isBlank()) {
                XWPFParagraph desc = doc.createParagraph();
                desc.setIndentationLeft(720);
                desc.createRun().setText(item.description().strip());
            }
        }
    }

    private void renderDecisions(XWPFDocument doc, java.util.List<DecisionRow> decisions) {
        if (decisions == null || decisions.isEmpty()) return;
        heading(doc, "决策");
        for (DecisionRow d : decisions) {
            String marker = "APPROVED".equals(d.status()) || "IMPLEMENTED".equals(d.status())
                ? " ✓" : "";
            bullet(doc, d.title() + marker);
            if (d.description() != null && !d.description().isBlank()) {
                XWPFParagraph p = doc.createParagraph();
                p.setIndentationLeft(720);
                p.createRun().setText(d.description().strip());
            }
        }
    }

    private void renderRisks(XWPFDocument doc, java.util.List<RiskRow> risks) {
        if (risks == null || risks.isEmpty()) return;
        heading(doc, "风险");
        for (RiskRow r : risks) {
            bullet(doc, (r.severity() == null ? "MEDIUM" : r.severity()) + ": " + r.title());
            if (r.description() != null && !r.description().isBlank()) {
                XWPFParagraph p = doc.createParagraph();
                p.setIndentationLeft(720);
                p.createRun().setText(r.description().strip());
            }
        }
    }

    private void renderWatermark(XWPFDocument doc, String watermarkText) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setItalic(true);
        r.setColor("808080");
        r.setText("— watermark: " + watermarkText + " —");
    }

    private void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading2");
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(14);
        r.setText(text);
    }

    private void bullet(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setNumID(NUM_ID);
        p.createRun().setText(text);
    }

    /**
     * Wire a minimal bullet numbering definition (id=1) so
     * {@code paragraph.setNumID(NUM_ID)} renders as a real bullet
     * instead of plain text. Apache POI requires the numbering doc to
     * exist before any paragraph references it.
     */
    private void ensureBulletNumbering(XWPFDocument doc) {
        XWPFNumbering numbering = doc.createNumbering();
        CTNumbering ct = CTNumbering.Factory.newInstance();
        CTAbstractNum abstractNum = ct.addNewAbstractNum();
        abstractNum.setAbstractNumId(BigInteger.ZERO);
        var lvl = abstractNum.addNewLvl();
        lvl.setIlvl(BigInteger.ZERO);
        lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
        lvl.addNewLvlText().setVal("•");
        // Wire the abstract definition into a concrete num id = 1.
        org.apache.poi.xwpf.usermodel.XWPFAbstractNum xwpfAbstract =
            new org.apache.poi.xwpf.usermodel.XWPFAbstractNum(abstractNum);
        BigInteger abstractNumID = numbering.addAbstractNum(xwpfAbstract);
        numbering.addNum(abstractNumID);
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
