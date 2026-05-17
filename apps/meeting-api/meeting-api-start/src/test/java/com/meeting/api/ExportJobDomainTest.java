package com.meeting.api;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.export.ExportJob;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportJobDomainTest {

    private static final OffsetDateTime CREATED_AT =
        OffsetDateTime.parse("2026-05-18T01:00:00Z");
    private static final OffsetDateTime AT_1 =
        OffsetDateTime.parse("2026-05-18T01:00:10Z");
    private static final OffsetDateTime AT_2 =
        OffsetDateTime.parse("2026-05-18T01:00:30Z");
    private static final OffsetDateTime EXPIRES_AT =
        OffsetDateTime.parse("2026-05-19T01:00:00Z");

    @Test
    void freshMeetingExportStartsQueuedWithDefaults() {
        ExportJob job = newPdfJob().build();
        assertThat(job.status()).isEqualTo(ExportStatus.QUEUED);
        assertThat(job.dataBoundaryMode()).isEqualTo(ExportDataBoundaryMode.FULL);
        assertThat(job.renderOptions()).isEqualTo(ExportRenderOptions.defaults());
        assertThat(job.fileId()).isNull();
        assertThat(job.fileHash()).isNull();
        assertThat(job.errorCode()).isNull();
        assertThat(job.downloadRevokedAt()).isNull();
        assertThat(job.finishedAt()).isNull();
        assertThat(job.isDownloadAvailable()).isFalse();
    }

    @Test
    void meetingExportRequiresMeetingId() {
        assertThatThrownBy(() -> newPdfJob().meetingId(null).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MEETING export requires meetingId");
    }

    @Test
    void meetingExportRequiresInputTranscriptVersion() {
        assertThatThrownBy(() -> newPdfJob().inputTranscriptVersion(null).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputTranscriptVersion");
    }

    @Test
    void rejectsNegativeVersions() {
        assertThatThrownBy(() -> newPdfJob().inputTranscriptVersion(-1).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(">= 0");
        assertThatThrownBy(() -> newPdfJob().inputMinutesVersion(-1).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(">= 0");
    }

    @Test
    void rejectsWatermarkOver200Chars() {
        String tooLong = "x".repeat(201);
        assertThatThrownBy(() -> newPdfJob().watermarkText(tooLong).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("<= 200 chars");
    }

    @Test
    void queuedToRunningTransition() {
        ExportJob job = newPdfJob().build();
        job.markRunning(AT_1);
        assertThat(job.status()).isEqualTo(ExportStatus.RUNNING);
        assertThat(job.updatedAt()).isEqualTo(AT_1);
    }

    @Test
    void markRunningIsIdempotent() {
        ExportJob job = newPdfJob().build();
        job.markRunning(AT_1);
        job.markRunning(AT_2);     // second call is a no-op, updatedAt does not move
        assertThat(job.status()).isEqualTo(ExportStatus.RUNNING);
        assertThat(job.updatedAt()).isEqualTo(AT_1);
    }

    @Test
    void runningToSucceededFillsFileAndExpiry() {
        ExportJob job = newPdfJob().build();
        job.markRunning(AT_1);
        job.markSucceeded("file_pdf_01", "sha256:abc", EXPIRES_AT, AT_2);
        assertThat(job.status()).isEqualTo(ExportStatus.SUCCEEDED);
        assertThat(job.fileId()).isEqualTo("file_pdf_01");
        assertThat(job.fileHash()).isEqualTo("sha256:abc");
        assertThat(job.downloadExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(job.finishedAt()).isEqualTo(AT_2);
        assertThat(job.isDownloadAvailable()).isTrue();
    }

    @Test
    void cannotSucceedFromQueued() {
        ExportJob job = newPdfJob().build();
        assertThatThrownBy(() -> job.markSucceeded("f", "h", EXPIRES_AT, AT_1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("QUEUED -> SUCCEEDED");
    }

    @Test
    void runningToFailedSetsErrorCode() {
        ExportJob job = newPdfJob().build();
        job.markRunning(AT_1);
        job.markFailed(ErrorCode.EXPORT_RENDER_FAILED, AT_2);
        assertThat(job.status()).isEqualTo(ExportStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo(ErrorCode.EXPORT_RENDER_FAILED);
        assertThat(job.finishedAt()).isEqualTo(AT_2);
    }

    @Test
    void queuedToFailedAllowsPreFlightRejection() {
        ExportJob job = newPdfJob().build();
        job.markFailed(ErrorCode.EXPORT_CONTENT_STALE, AT_1);
        assertThat(job.status()).isEqualTo(ExportStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo(ErrorCode.EXPORT_CONTENT_STALE);
    }

    @Test
    void cancelFromQueuedOrRunning() {
        ExportJob a = newPdfJob().build();
        a.markCancelled(AT_1);
        assertThat(a.status()).isEqualTo(ExportStatus.CANCELLED);

        ExportJob b = newPdfJob().build();
        b.markRunning(AT_1);
        b.markCancelled(AT_2);
        assertThat(b.status()).isEqualTo(ExportStatus.CANCELLED);
    }

    @Test
    void cannotCancelTerminalJob() {
        ExportJob job = newPdfJob().build();
        job.markRunning(AT_1);
        job.markSucceeded("f", "h", EXPIRES_AT, AT_2);
        assertThatThrownBy(() -> job.markCancelled(AT_2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SUCCEEDED -> CANCELLED");
    }

    @Test
    void revokeFromSucceededFlipsToRevoked() {
        ExportJob job = newPdfJob().build();
        job.markRunning(AT_1);
        job.markSucceeded("f", "h", EXPIRES_AT, AT_2);
        OffsetDateTime revokeAt = AT_2.plusMinutes(5);
        job.revokeDownload(revokeAt);
        assertThat(job.status()).isEqualTo(ExportStatus.REVOKED);
        assertThat(job.downloadRevokedAt()).isEqualTo(revokeAt);
        assertThat(job.isDownloadAvailable()).isFalse();
    }

    @Test
    void revokeIsIdempotent() {
        ExportJob job = newPdfJob().build();
        job.markRunning(AT_1);
        job.markSucceeded("f", "h", EXPIRES_AT, AT_2);
        OffsetDateTime first = AT_2.plusMinutes(1);
        job.revokeDownload(first);
        job.revokeDownload(first.plusMinutes(5));  // no-op
        assertThat(job.downloadRevokedAt()).isEqualTo(first);
    }

    @Test
    void cannotRevokeFromQueuedOrFailed() {
        ExportJob queued = newPdfJob().build();
        assertThatThrownBy(() -> queued.revokeDownload(AT_1))
            .isInstanceOf(IllegalStateException.class);

        ExportJob failed = newPdfJob().build();
        failed.markRunning(AT_1);
        failed.markFailed(ErrorCode.EXPORT_RENDER_FAILED, AT_2);
        assertThatThrownBy(() -> failed.revokeDownload(AT_2))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exportStatusIsTerminalReflectsTerminalSet() {
        assertThat(ExportStatus.QUEUED.isTerminal()).isFalse();
        assertThat(ExportStatus.RUNNING.isTerminal()).isFalse();
        assertThat(ExportStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(ExportStatus.FAILED.isTerminal()).isTrue();
        assertThat(ExportStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(ExportStatus.REVOKED.isTerminal()).isTrue();
    }

    private ExportJob.Builder newPdfJob() {
        return ExportJob.builder()
            .id("exp_test_01")
            .tenantId("tenant_test_01")
            .meetingId("mtg_test_01")
            .exportType(ExportType.MEETING)
            .format(ExportFormat.PDF)
            .inputTranscriptVersion(3)
            .inputMinutesVersion(2)
            .snapshotManifestId("mfst_test_01")
            .createdBy("user_test_01")
            .createdAt(CREATED_AT);
    }
}
