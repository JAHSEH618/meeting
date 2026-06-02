package com.meeting.api;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingTaskDomainTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-05-13T02:00:00Z");

    @Test
    void heartbeatAndWorkerCompleteKeepTaskRunningUntilTerminalPhase() {
        ProcessingTask task = newTask();

        task.enqueue(now);
        task.claimLease("worker_01", "worker_01:task_01:1", now.plusMinutes(5), now.plusSeconds(1));
        task.heartbeat(
            ProcessingStep.AUDIO_PREPROCESS,
            35,
            1,
            "worker_01:task_01:1",
            now.plusSeconds(20),
            now.plusMinutes(6)
        );

        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.WORKER_DAG_RUNNING);
        assertThat(task.step(ProcessingStep.AUDIO_PREPROCESS).progress()).isEqualTo(35);
        assertThat(task.leaseExpiresAt()).isEqualTo(now.plusMinutes(6));

        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE),
            List.of(),
            1,
            "worker_01:task_01:1",
            now.plusMinutes(2)
        );

        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.WORKER_DAG_DONE);
        assertThat(task.step(ProcessingStep.ASR).status()).isEqualTo(StepStatus.SUCCEEDED);
    }

    @Test
    void workerPhaseCompleteCannotMarkJavaOwnedCompletedSteps() {
        ProcessingTask task = newTask();
        task.enqueue(now);
        task.claimLease("worker_01", "worker_01:task_01:1", now.plusMinutes(5), now);

        assertThatThrownBy(() -> task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.SUMMARY),
            List.of(),
            1,
            "worker_01:task_01:1",
            now.plusMinutes(2)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ai-worker callback");

        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.WORKER_DAG_RUNNING);
        assertThat(task.step(ProcessingStep.SUMMARY).status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void workerPhaseCompleteCannotSkipJavaOwnedSteps() {
        ProcessingTask task = newTask();
        task.enqueue(now);
        task.claimLease("worker_01", "worker_01:task_01:1", now.plusMinutes(5), now);

        assertThatThrownBy(() -> task.completeWorkerPhase(
            ProcessingTaskStatus.PARTIAL_SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS),
            List.of(new WorkerPhaseCompletedEvent.SkippedStep(ProcessingStep.EXTRACTION, "not worker owned")),
            1,
            "worker_01:task_01:1",
            now.plusMinutes(2)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ai-worker callback");

        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.WORKER_DAG_RUNNING);
        assertThat(task.step(ProcessingStep.EXTRACTION).status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void staleAttemptAndLeaseCannotUpdateCurrentTask() {
        ProcessingTask task = newTask();
        task.enqueue(now);
        task.claimLease("worker_01", "worker_01:task_01:1", now.plusMinutes(5), now);

        assertThatThrownBy(() -> task.updateWorkerStep(
            ProcessingStep.ASR,
            StepStatus.SUCCEEDED,
            100,
            2,
            "worker_01:task_01:1",
            "worker_01",
            null,
            now.plusSeconds(10)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("attempt");

        assertThatThrownBy(() -> task.updateWorkerStep(
            ProcessingStep.ASR,
            StepStatus.SUCCEEDED,
            100,
            1,
            "other-lease",
            "worker_01",
            null,
            now.plusSeconds(10)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lease");
    }

    @Test
    void expiredLeaseBecomesOrphanedAndRequeueIncrementsAttempt() {
        ProcessingTask task = newTask();
        task.enqueue(now);
        task.claimLease("worker_01", "worker_01:task_01:1", now.plusSeconds(30), now);

        assertThat(task.markOrphanedIfLeaseExpired(now.plusSeconds(31))).isTrue();
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.ORPHANED);
        assertThat(task.leaseOwner()).isNull();

        task.requeueOrphaned(now.plusSeconds(35));

        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.QUEUED);
        assertThat(task.attemptNo()).isEqualTo(2);
        assertThat(task.step(ProcessingStep.AUDIO_PREPROCESS).status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void cancelMovesThroughCancelPendingToTerminalCancelled() {
        ProcessingTask task = newTask();
        task.enqueue(now);

        task.requestCancel(now.plusSeconds(1));
        task.confirmCancelled(now.plusSeconds(2));

        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.CANCELLED);
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
    }

    @Test
    void javaStepTerminalStatesPreserveAttemptNoSoSameRowIsUpdated() {
        // The JDBC step row is keyed by (task_id, step_name, attempt_count).
        // If markJavaStepSucceeded/Failed drop attemptNo back to null while
        // markJavaStepRunning recorded it as the task's attempt, the next
        // INSERT...ON CONFLICT lands on a different attempt_count and a
        // stale RUNNING row survives. completeJavaPhase then refuses to
        // close the task because that row says "still in progress".
        ProcessingTask succeededTask = ProcessingTask.create(
            "task_java_ok", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_UPLOAD, ProcessingStep.SUMMARY),
            now
        );
        succeededTask.markJavaStepRunning(ProcessingStep.SUMMARY, 50, now);
        assertThat(succeededTask.step(ProcessingStep.SUMMARY).attemptNo())
            .as("RUNNING records the task's attempt")
            .isEqualTo(succeededTask.attemptNo());

        succeededTask.markJavaStepSucceeded(ProcessingStep.SUMMARY, now.plusSeconds(1));
        assertThat(succeededTask.step(ProcessingStep.SUMMARY).attemptNo())
            .as("SUCCEEDED must keep the same attempt so the running row is updated, not duplicated")
            .isEqualTo(succeededTask.attemptNo());

        ProcessingTask failedTask = ProcessingTask.create(
            "task_java_fail", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_UPLOAD, ProcessingStep.SUMMARY),
            now
        );
        failedTask.markJavaStepRunning(ProcessingStep.SUMMARY, 25, now);
        failedTask.markJavaStepFailed(ProcessingStep.SUMMARY, "LLM_ERROR", now.plusSeconds(1));
        assertThat(failedTask.step(ProcessingStep.SUMMARY).attemptNo())
            .as("FAILED must also preserve the running attempt")
            .isEqualTo(failedTask.attemptNo());
    }

    private ProcessingTask newTask() {
        return ProcessingTask.create(
            "task_01",
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            List.of(
                ProcessingStep.AUDIO_UPLOAD,
                ProcessingStep.AUDIO_PREPROCESS,
                ProcessingStep.ASR,
                ProcessingStep.TRANSCRIPT_MERGE,
                ProcessingStep.SUMMARY,
                ProcessingStep.EXTRACTION
            ),
            now
        );
    }
}
