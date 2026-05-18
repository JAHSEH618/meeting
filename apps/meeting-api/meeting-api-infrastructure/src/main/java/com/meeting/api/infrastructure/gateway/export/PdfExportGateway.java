package com.meeting.api.infrastructure.gateway.export;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PDF implementation of {@link ExportGateway} that re-uses
 * {@link DocxExportGateway} to produce a DOCX and then shells out to
 * LibreOffice headless ({@code soffice --headless --convert-to pdf}) to
 * convert it. We avoid bundling a pure-Java PDF engine because:
 *
 * <ul>
 *   <li>The DOCX layout is already correct and can be re-used 1:1, so
 *       maintaining a second renderer doubles the work for every layout
 *       change.</li>
 *   <li>LibreOffice handles CJK fonts, headings, lists, page breaks and
 *       watermarks consistently with Word.</li>
 * </ul>
 *
 * <p>Phase 7.3.4.e relies on this gateway being independently injectable
 * (for the deletion-certificate PDF copy). Keep it free of any
 * dependency on {@code ExportQueueConsumer} state.
 *
 * <p>Operational contract:
 * <ul>
 *   <li>The {@code soffice} binary path is configurable via
 *       {@code meeting.export.libreoffice.binary}; default {@code soffice}.</li>
 *   <li>Subprocess wall-clock timeout via
 *       {@code meeting.export.libreoffice.timeout-seconds} (default 60).</li>
 *   <li>All temp files live in a per-render directory under the system
 *       tmp dir and are removed in a {@code finally} block, including on
 *       failure.</li>
 *   <li>Failures throw {@link ExportRuntimeException} with
 *       {@link ErrorCode#EXPORT_RENDER_FAILED} so the consumer retries
 *       up to its configured cap before letting the message hit DLQ.</li>
 * </ul>
 */
@Component
public class PdfExportGateway implements ExportGateway {

    private static final Logger log = LoggerFactory.getLogger(PdfExportGateway.class);

    private final DocxExportGateway docxGateway;
    private final String binary;
    private final long timeoutSeconds;

    public PdfExportGateway(
        DocxExportGateway docxGateway,
        @Value("${meeting.export.libreoffice.binary:soffice}") String binary,
        @Value("${meeting.export.libreoffice.timeout-seconds:60}") long timeoutSeconds
    ) {
        this.docxGateway = docxGateway;
        this.binary = binary;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.PDF;
    }

    @Override
    public RenderedFile render(ExportJob job, MeetingSnapshot snapshot) {
        RenderedFile docxOut = docxGateway.render(job, snapshot);
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("meeting-export-pdf-");
            Path docxPath = workDir.resolve(job.id() + ".docx");
            Files.write(docxPath, docxOut.bytes());

            byte[] pdfBytes = convertToPdf(docxPath, workDir, job.id());
            return new RenderedFile(pdfBytes, sha256Hex(pdfBytes), pdfBytes.length);
        } catch (IOException ex) {
            throw new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED,
                "PDF render IO failure for export " + job.id() + ": " + ex.getMessage(),
                ex
            );
        } finally {
            if (workDir != null) {
                cleanup(workDir);
            }
        }
    }

    private byte[] convertToPdf(Path docxPath, Path workDir, String exportId) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            binary, "--headless",
            "-env:UserInstallation=file://" + workDir.resolve("profile").toString(),
            "--convert-to", "pdf",
            "--outdir", workDir.toString(),
            docxPath.toString()
        ).redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException ex) {
            throw new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED,
                "LibreOffice binary '" + binary + "' not invokable: " + ex.getMessage(),
                ex
            );
        }

        String stdout = drainStdout(process);
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED,
                "LibreOffice subprocess interrupted for export " + exportId,
                ex
            );
        }
        if (!finished) {
            process.destroyForcibly();
            throw new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED,
                "LibreOffice subprocess exceeded " + timeoutSeconds + "s for export " + exportId
            );
        }
        if (process.exitValue() != 0) {
            throw new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED,
                "LibreOffice exited " + process.exitValue() + " for export " + exportId
                    + " (stdout: " + truncate(stdout, 400) + ")"
            );
        }

        Path pdfPath = workDir.resolve(exportId + ".pdf");
        if (!Files.exists(pdfPath)) {
            throw new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED,
                "LibreOffice exited 0 but produced no PDF for export " + exportId
                    + " (stdout: " + truncate(stdout, 400) + ")"
            );
        }
        return Files.readAllBytes(pdfPath);
    }

    private static String drainStdout(Process process) throws IOException {
        try (InputStream in = process.getInputStream()) {
            return new String(in.readAllBytes());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort; the OS tmp janitor sweeps the rest.
                }
            });
        } catch (IOException ex) {
            log.warn("pdf_export_cleanup_failed dir={} reason={}", dir, ex.getMessage());
        }
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
