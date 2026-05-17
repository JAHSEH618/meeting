package com.meeting.api;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionJob;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeletionJobDomainTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T04:00:00Z");

    @Test
    void freshJobIsRequestedAndNotLegalHoldChecked() {
        DeletionJob job = sampleJob().build();
        assertThat(job.status()).isEqualTo(DeletionJobStatus.REQUESTED);
        assertThat(job.legalHoldChecked()).isFalse();
        assertThat(job.deletedRowsJson()).isEmpty();
        assertThat(job.finishedAt()).isNull();
        assertThat(job.errorCode()).isNull();
    }

    @Test
    void markRunningTransitionsAndSetsLegalHoldChecked() {
        DeletionJob job = sampleJob().build();
        job.markRunning(NOW);
        assertThat(job.status()).isEqualTo(DeletionJobStatus.RUNNING);
        assertThat(job.legalHoldChecked()).isTrue();
    }

    @Test
    void markRunningFromTerminalRejected() {
        DeletionJob job = sampleJob().status(DeletionJobStatus.SUCCEEDED).build();
        assertThatThrownBy(() -> job.markRunning(NOW))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SUCCEEDED -> RUNNING");
    }

    @Test
    void markSucceededFillsAuditFields() {
        DeletionJob job = sampleJob().build();
        job.markRunning(NOW);
        OffsetDateTime done = NOW.plusMinutes(5);

        job.markSucceeded(
            Map.of("meetings", 1),
            Map.of("files", 3),
            Map.of("keys", 1),
            "sha256:cert_hash",
            done
        );

        assertThat(job.status()).isEqualTo(DeletionJobStatus.SUCCEEDED);
        assertThat(job.deletedRowsJson()).containsEntry("meetings", 1);
        assertThat(job.deletedFilesJson()).containsEntry("files", 3);
        assertThat(job.kmsKeysDestroyedJson()).containsEntry("keys", 1);
        assertThat(job.certificateHash()).isEqualTo("sha256:cert_hash");
        assertThat(job.finishedAt()).isEqualTo(done);
    }

    @Test
    void markPartialFailedStillCapturesPartialResults() {
        DeletionJob job = sampleJob().build();
        job.markRunning(NOW);
        OffsetDateTime done = NOW.plusMinutes(5);

        job.markPartialFailed(
            Map.of("meetings", 1),
            Map.of("files", 2, "failed", 1),
            Map.of(),
            "sha256:partial",
            done
        );

        assertThat(job.status()).isEqualTo(DeletionJobStatus.PARTIAL_FAILED);
        assertThat(job.deletedFilesJson()).containsEntry("failed", 1);
        assertThat(job.certificateHash()).isEqualTo("sha256:partial");
    }

    @Test
    void markFailedRecordsErrorCode() {
        DeletionJob job = sampleJob().build();
        job.markRunning(NOW);
        OffsetDateTime done = NOW.plusMinutes(2);

        job.markFailed(ErrorCode.INTERNAL_ERROR, done);

        assertThat(job.status()).isEqualTo(DeletionJobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(job.finishedAt()).isEqualTo(done);
    }

    @Test
    void markBlockedByLegalHoldRecordsErrorCode() {
        DeletionJob job = sampleJob().build();
        job.markBlockedByLegalHold(NOW);
        assertThat(job.status()).isEqualTo(DeletionJobStatus.BLOCKED_BY_LEGAL_HOLD);
        assertThat(job.errorCode()).isEqualTo(ErrorCode.DELETION_JOB_BLOCKED_BY_LEGAL_HOLD);
        assertThat(job.legalHoldChecked()).isTrue();
        assertThat(job.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void markBlockedFromRunningRejected() {
        DeletionJob job = sampleJob().build();
        job.markRunning(NOW);
        assertThatThrownBy(() -> job.markBlockedByLegalHold(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankScopeId() {
        assertThatThrownBy(() -> sampleJob().scopeId("").build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankRequestedBy() {
        assertThatThrownBy(() -> sampleJob().requestedBy(" ").build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    private DeletionJob.Builder sampleJob() {
        return DeletionJob.builder()
            .id("dj_test_01")
            .tenantId("tenant_test_01")
            .scopeType(DeletionScopeType.MEETING)
            .scopeId("mtg_test_01")
            .requestedBy("user_compliance")
            .createdAt(NOW);
    }
}
