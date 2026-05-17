package com.meeting.api.domain.export;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.export.ExportRenderOptions;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Aggregate root for an asynchronous export job (phase 6).
 *
 * <p>An export job is born in {@link ExportStatus#QUEUED}; the
 * {@code export-queue} consumer pulls it, transitions to
 * {@link ExportStatus#RUNNING}, hands it to a format-specific
 * {@code ExportGateway}, then either:
 * <ul>
 *   <li>{@link #markSucceeded} the job and stamps the rendered file's
 *       hash and download expiry;</li>
 *   <li>{@link #markFailed} with a stable {@link ErrorCode}; or</li>
 *   <li>{@link #markCancelled} if a user requested cancel before
 *       completion.</li>
 * </ul>
 *
 * <p>After success the download URL can later be {@link #revokeDownload
 * revoked} (terminal {@link ExportStatus#REVOKED}), which is one-way —
 * a revoked download cannot be re-issued without creating a new job.
 *
 * <p>State machine (anything not listed throws {@link IllegalStateException}):
 * <pre>
 *   QUEUED  ─► RUNNING        (markRunning)
 *   QUEUED  ─► CANCELLED      (markCancelled)
 *   QUEUED  ─► FAILED         (markFailed — pre-flight rejection)
 *   RUNNING ─► SUCCEEDED      (markSucceeded)
 *   RUNNING ─► FAILED         (markFailed)
 *   RUNNING ─► CANCELLED      (markCancelled)
 *   SUCCEEDED ─► REVOKED      (revokeDownload)
 * </pre>
 *
 * <p>Identity is the export id ({@code exp_<uuidNoDash>}). Equality is
 * deliberately not defined — repositories key on the id field directly.
 */
public final class ExportJob {

    private final String id;
    private final String tenantId;
    private final String meetingId;
    private final ExportType exportType;
    private final ExportFormat format;
    private final ExportDataBoundaryMode dataBoundaryMode;
    private final Integer inputTranscriptVersion;
    private final Integer inputMinutesVersion;
    private final String snapshotManifestId;
    private final String watermarkText;
    private final ExportRenderOptions renderOptions;
    private final String createdBy;
    private final OffsetDateTime createdAt;

    private ExportStatus status;
    private String fileId;
    private String fileHash;
    private OffsetDateTime downloadExpiresAt;
    private OffsetDateTime downloadRevokedAt;
    private ErrorCode errorCode;
    private OffsetDateTime updatedAt;
    private OffsetDateTime finishedAt;

    private ExportJob(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
        this.exportType = Objects.requireNonNull(b.exportType, "exportType");
        this.format = Objects.requireNonNull(b.format, "format");
        this.dataBoundaryMode = b.dataBoundaryMode == null ? ExportDataBoundaryMode.FULL : b.dataBoundaryMode;
        this.meetingId = b.meetingId;
        this.inputTranscriptVersion = b.inputTranscriptVersion;
        this.inputMinutesVersion = b.inputMinutesVersion;
        this.snapshotManifestId = b.snapshotManifestId;
        this.watermarkText = b.watermarkText;
        this.renderOptions = b.renderOptions == null ? ExportRenderOptions.defaults() : b.renderOptions;
        this.createdBy = b.createdBy;
        this.status = b.status == null ? ExportStatus.QUEUED : b.status;
        this.fileId = b.fileId;
        this.fileHash = b.fileHash;
        this.downloadExpiresAt = b.downloadExpiresAt;
        this.downloadRevokedAt = b.downloadRevokedAt;
        this.errorCode = b.errorCode;
        this.createdAt = b.createdAt == null ? OffsetDateTime.now() : b.createdAt;
        this.updatedAt = b.updatedAt == null ? this.createdAt : b.updatedAt;
        this.finishedAt = b.finishedAt;

        if (exportType == ExportType.MEETING) {
            if (meetingId == null) {
                throw new IllegalArgumentException("MEETING export requires meetingId");
            }
            if (inputTranscriptVersion == null) {
                throw new IllegalArgumentException(
                    "MEETING export requires inputTranscriptVersion (got null)"
                );
            }
            if (inputTranscriptVersion < 0) {
                throw new IllegalArgumentException(
                    "inputTranscriptVersion must be >= 0, got " + inputTranscriptVersion
                );
            }
        }
        if (inputMinutesVersion != null && inputMinutesVersion < 0) {
            throw new IllegalArgumentException(
                "inputMinutesVersion must be >= 0 when present, got " + inputMinutesVersion
            );
        }
        if (watermarkText != null && watermarkText.length() > 200) {
            throw new IllegalArgumentException(
                "watermarkText must be <= 200 chars, got " + watermarkText.length()
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String meetingId() { return meetingId; }
    public ExportType exportType() { return exportType; }
    public ExportFormat format() { return format; }
    public ExportDataBoundaryMode dataBoundaryMode() { return dataBoundaryMode; }
    public Integer inputTranscriptVersion() { return inputTranscriptVersion; }
    public Integer inputMinutesVersion() { return inputMinutesVersion; }
    public String snapshotManifestId() { return snapshotManifestId; }
    public String watermarkText() { return watermarkText; }
    public ExportRenderOptions renderOptions() { return renderOptions; }
    public String createdBy() { return createdBy; }
    public OffsetDateTime createdAt() { return createdAt; }
    public ExportStatus status() { return status; }
    public String fileId() { return fileId; }
    public String fileHash() { return fileHash; }
    public OffsetDateTime downloadExpiresAt() { return downloadExpiresAt; }
    public OffsetDateTime downloadRevokedAt() { return downloadRevokedAt; }
    public ErrorCode errorCode() { return errorCode; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public OffsetDateTime finishedAt() { return finishedAt; }

    public boolean isDownloadAvailable() {
        return status == ExportStatus.SUCCEEDED && downloadRevokedAt == null;
    }

    /** QUEUED → RUNNING. Idempotent on RUNNING (no-op if already running). */
    public void markRunning(OffsetDateTime at) {
        if (status == ExportStatus.RUNNING) {
            return;
        }
        require(status == ExportStatus.QUEUED, "RUNNING");
        this.status = ExportStatus.RUNNING;
        touch(at);
    }

    /** RUNNING → SUCCEEDED. */
    public void markSucceeded(
        String fileId, String fileHash, OffsetDateTime expiresAt, OffsetDateTime at
    ) {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(fileHash, "fileHash");
        Objects.requireNonNull(expiresAt, "expiresAt");
        require(status == ExportStatus.RUNNING, "SUCCEEDED");
        this.status = ExportStatus.SUCCEEDED;
        this.fileId = fileId;
        this.fileHash = fileHash;
        this.downloadExpiresAt = expiresAt;
        this.errorCode = null;
        this.finishedAt = at;
        touch(at);
    }

    /** QUEUED|RUNNING → FAILED. */
    public void markFailed(ErrorCode errorCode, OffsetDateTime at) {
        Objects.requireNonNull(errorCode, "errorCode");
        require(
            status == ExportStatus.QUEUED || status == ExportStatus.RUNNING,
            "FAILED"
        );
        this.status = ExportStatus.FAILED;
        this.errorCode = errorCode;
        this.finishedAt = at;
        touch(at);
    }

    /** QUEUED|RUNNING → CANCELLED. */
    public void markCancelled(OffsetDateTime at) {
        require(
            status == ExportStatus.QUEUED || status == ExportStatus.RUNNING,
            "CANCELLED"
        );
        this.status = ExportStatus.CANCELLED;
        this.finishedAt = at;
        touch(at);
    }

    /**
     * SUCCEEDED → REVOKED. One-way; a revoked download cannot be re-issued.
     * Idempotent on REVOKED (no-op).
     */
    public void revokeDownload(OffsetDateTime at) {
        if (status == ExportStatus.REVOKED) {
            return;
        }
        require(status == ExportStatus.SUCCEEDED, "REVOKED");
        this.status = ExportStatus.REVOKED;
        this.downloadRevokedAt = at;
        touch(at);
    }

    private void require(boolean predicate, String target) {
        if (!predicate) {
            throw new IllegalStateException(
                "ExportJob " + id + " cannot transition " + status + " -> " + target
            );
        }
    }

    private void touch(OffsetDateTime at) {
        this.updatedAt = at == null ? OffsetDateTime.now() : at;
    }

    public static final class Builder {
        private String id;
        private String tenantId;
        private String meetingId;
        private ExportType exportType;
        private ExportFormat format;
        private ExportDataBoundaryMode dataBoundaryMode;
        private Integer inputTranscriptVersion;
        private Integer inputMinutesVersion;
        private String snapshotManifestId;
        private String watermarkText;
        private ExportRenderOptions renderOptions;
        private String createdBy;
        private ExportStatus status;
        private String fileId;
        private String fileHash;
        private OffsetDateTime downloadExpiresAt;
        private OffsetDateTime downloadRevokedAt;
        private ErrorCode errorCode;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private OffsetDateTime finishedAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder meetingId(String v) { this.meetingId = v; return this; }
        public Builder exportType(ExportType v) { this.exportType = v; return this; }
        public Builder format(ExportFormat v) { this.format = v; return this; }
        public Builder dataBoundaryMode(ExportDataBoundaryMode v) { this.dataBoundaryMode = v; return this; }
        public Builder inputTranscriptVersion(Integer v) { this.inputTranscriptVersion = v; return this; }
        public Builder inputMinutesVersion(Integer v) { this.inputMinutesVersion = v; return this; }
        public Builder snapshotManifestId(String v) { this.snapshotManifestId = v; return this; }
        public Builder watermarkText(String v) { this.watermarkText = v; return this; }
        public Builder renderOptions(ExportRenderOptions v) { this.renderOptions = v; return this; }
        public Builder createdBy(String v) { this.createdBy = v; return this; }
        public Builder status(ExportStatus v) { this.status = v; return this; }
        public Builder fileId(String v) { this.fileId = v; return this; }
        public Builder fileHash(String v) { this.fileHash = v; return this; }
        public Builder downloadExpiresAt(OffsetDateTime v) { this.downloadExpiresAt = v; return this; }
        public Builder downloadRevokedAt(OffsetDateTime v) { this.downloadRevokedAt = v; return this; }
        public Builder errorCode(ErrorCode v) { this.errorCode = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(OffsetDateTime v) { this.updatedAt = v; return this; }
        public Builder finishedAt(OffsetDateTime v) { this.finishedAt = v; return this; }

        public ExportJob build() {
            return new ExportJob(this);
        }
    }
}
