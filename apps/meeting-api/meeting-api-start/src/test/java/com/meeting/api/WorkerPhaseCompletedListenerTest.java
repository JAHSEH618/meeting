package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.app.task.WorkerPhaseCompletedListener;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPhaseCompletedListenerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T03:00:00Z");

    @Test
    void meetingFullPipelineTransitionsToJavaLlmRunning() {
        InMemoryTaskRepository tasks = workerDagDoneTask("MEETING_FULL_PIPELINE", true);
        TaskStepProgressService progress = service(tasks);
        WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(progress);

        listener.onWorkerPhaseCompleted(workerEvent("MEETING_FULL_PIPELINE", ProcessingTaskStatus.SUCCEEDED));

        ProcessingTask task = tasks.findById("tenant_01", "task_01").orElseThrow();
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
    }

    @Test
    void speakerEnrollmentTransitionsDirectlyToTerminalSucceeded() {
        InMemoryTaskRepository tasks = workerDagDoneTask("SPEAKER_ENROLLMENT", false);
        TaskStepProgressService progress = service(tasks);
        WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(progress);

        listener.onWorkerPhaseCompleted(workerEvent("SPEAKER_ENROLLMENT", ProcessingTaskStatus.SUCCEEDED));

        ProcessingTask task = tasks.findById("tenant_01", "task_01").orElseThrow();
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.SUCCEEDED);
    }

    @Test
    void textEmbeddingTransitionsDirectlyToTerminalPartialSucceeded() {
        InMemoryTaskRepository tasks = workerDagDoneTask("TEXT_EMBEDDING", false);
        TaskStepProgressService progress = service(tasks);
        WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(progress);

        listener.onWorkerPhaseCompleted(workerEvent("TEXT_EMBEDDING", ProcessingTaskStatus.PARTIAL_SUCCEEDED));

        ProcessingTask task = tasks.findById("tenant_01", "task_01").orElseThrow();
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.PARTIAL_SUCCEEDED);
    }

    @Test
    void listenerSwallowsExceptionsFromService() {
        InMemoryTaskRepository tasks = workerDagDoneTask("MEETING_FULL_PIPELINE", true);
        TaskStepProgressService progress = service(tasks);
        WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(progress);

        // Fire on an unknown task; service throws but listener must not propagate.
        listener.onWorkerPhaseCompleted(new WorkerPhaseCompletedEvent(
            "evt",
            "tenant_01",
            "unknown_task",
            "MEETING_FULL_PIPELINE",
            1,
            ProcessingTaskStatus.SUCCEEDED,
            List.of(),
            List.of(),
            null,
            0,
            NOW
        ));
    }

    private static TaskStepProgressService service(InMemoryTaskRepository tasks) {
        return new TaskStepProgressService(
            tasks,
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static WorkerPhaseCompletedEvent workerEvent(String taskType, ProcessingTaskStatus workerStatus) {
        return new WorkerPhaseCompletedEvent(
            "evt",
            "tenant_01",
            "task_01",
            taskType,
            1,
            workerStatus,
            List.of(),
            List.of(),
            null,
            0,
            NOW
        );
    }

    private static InMemoryTaskRepository workerDagDoneTask(String taskType, boolean withJavaSteps) {
        List<ProcessingStep> steps = withJavaSteps
            ? List.of(
                ProcessingStep.AUDIO_UPLOAD,
                ProcessingStep.AUDIO_PREPROCESS,
                ProcessingStep.ASR,
                ProcessingStep.TRANSCRIPT_MERGE,
                ProcessingStep.SUMMARY,
                ProcessingStep.EXTRACTION
            )
            : List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING);
        ProcessingTask task = ProcessingTask.create("task_01", "tenant_01", "meeting_01", taskType, steps, NOW);
        if (withJavaSteps) {
            task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        }
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        List<ProcessingStep> workerSteps = withJavaSteps
            ? List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE)
            : steps;
        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            workerSteps,
            List.<WorkerPhaseCompletedEvent.SkippedStep>of(),
            1,
            "worker_01:task_01:1",
            NOW.plusMinutes(1)
        );
        return new InMemoryTaskRepository(task);
    }

    private static final class InMemoryTaskRepository implements ProcessingTaskRepository {
        private ProcessingTask task;

        private InMemoryTaskRepository(ProcessingTask task) {
            this.task = task;
        }

        @Override
        public ProcessingTask save(ProcessingTask task) {
            this.task = task;
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return tenantId.equals(task.tenantId()) && taskId.equals(task.taskId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(OffsetDateTime now, int limit) {
            return List.of();
        }
    }
}
