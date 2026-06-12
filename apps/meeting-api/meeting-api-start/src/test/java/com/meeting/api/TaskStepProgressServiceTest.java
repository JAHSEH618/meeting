package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStepProgressServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T02:00:00Z");

    @Test
    void beginJavaPhaseTransitionsWorkerDagDoneToJavaLlmRunning() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);

        var dto = service.beginJavaPhase("tenant_01", "task_01");

        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
    }

    @Test
    void beginJavaPhaseIsIdempotentWhenAlreadyJavaLlmRunning() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);

        service.beginJavaPhase("tenant_01", "task_01");
        var dto = service.beginJavaPhase("tenant_01", "task_01");

        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
    }

    @Test
    void markStepRunningAdvancesJavaOwnedStep() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);
        service.beginJavaPhase("tenant_01", "task_01");

        var dto = service.markStepRunning("tenant_01", "task_01", ProcessingStep.SUMMARY, 10);

        assertThat(dto.currentStep()).isEqualTo("SUMMARY");
        assertThat(dto.steps())
            .filteredOn(step -> step.stepName() == ProcessingStep.SUMMARY)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.status()).isEqualTo(StepStatus.RUNNING);
                assertThat(step.progress()).isEqualTo(10);
            });
    }

    @Test
    void markStepRunningOnWorkerOwnedStepIsRejected() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);
        service.beginJavaPhase("tenant_01", "task_01");

        assertThatThrownBy(() -> service.markStepRunning("tenant_01", "task_01", ProcessingStep.ASR, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Java task service");
    }

    @Test
    void completeJavaPhaseWithAllSucceededTransitionsToTerminalSucceeded() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);
        service.beginJavaPhase("tenant_01", "task_01");
        service.markStepRunning("tenant_01", "task_01", ProcessingStep.SUMMARY, 50);
        service.markStepSucceeded("tenant_01", "task_01", ProcessingStep.SUMMARY);
        service.markStepSucceeded("tenant_01", "task_01", ProcessingStep.EXTRACTION);

        var dto = service.completeJavaPhase("tenant_01", "task_01");

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.SUCCEEDED);
        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
    }

    @Test
    void completeJavaPhaseWithFailedStepTransitionsToTerminalFailed() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);
        service.beginJavaPhase("tenant_01", "task_01");
        service.markStepFailed("tenant_01", "task_01", ProcessingStep.SUMMARY, "LLM_PROVIDER_TIMEOUT");
        service.markStepSucceeded("tenant_01", "task_01", ProcessingStep.EXTRACTION);

        var dto = service.completeJavaPhase("tenant_01", "task_01");

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.FAILED);
        assertThat(dto.lastErrorCode()).isEqualTo("LLM_PROVIDER_TIMEOUT");
    }

    @Test
    void completeJavaPhaseWithExtractionFailedKeepsSummaryAsPartialSuccess() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);
        service.beginJavaPhase("tenant_01", "task_01");
        service.markStepSucceeded("tenant_01", "task_01", ProcessingStep.SUMMARY);
        service.markStepFailed("tenant_01", "task_01", ProcessingStep.EXTRACTION, "ITEM_EXTRACTION_TIMEOUT");

        var dto = service.completeJavaPhase("tenant_01", "task_01");

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.PARTIAL_SUCCEEDED);
        assertThat(dto.lastErrorCode()).isEqualTo("ITEM_EXTRACTION_TIMEOUT");
    }

    @Test
    void completeJavaPhaseRejectsPendingJavaStep() {
        InMemoryTaskRepository tasks = workerDagDoneTask();
        TaskStepProgressService service = service(tasks);
        service.beginJavaPhase("tenant_01", "task_01");
        service.markStepSucceeded("tenant_01", "task_01", ProcessingStep.SUMMARY);

        assertThatThrownBy(() -> service.completeJavaPhase("tenant_01", "task_01"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Java step still in progress");
    }

    @Test
    void completeWithoutJavaPhaseSkipsLlmStage() {
        InMemoryTaskRepository tasks = workerDagDoneTaskWithoutJavaSteps();
        TaskStepProgressService service = service(tasks);

        var dto = service.completeWithoutJavaPhase("tenant_01", "task_01", ProcessingTaskStatus.SUCCEEDED, null);

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.SUCCEEDED);
        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
    }

    private static TaskStepProgressService service(InMemoryTaskRepository tasks) {
        return new TaskStepProgressService(
            tasks,
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static InMemoryTaskRepository workerDagDoneTask() {
        ProcessingTask task = ProcessingTask.create(
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
            NOW
        );
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE),
            List.<WorkerPhaseCompletedEvent.SkippedStep>of(),
            1,
            "worker_01:task_01:1",
            NOW.plusMinutes(1)
        );
        return new InMemoryTaskRepository(task);
    }

    private static InMemoryTaskRepository workerDagDoneTaskWithoutJavaSteps() {
        ProcessingTask task = ProcessingTask.create(
            "task_01",
            "tenant_01",
            null,
            "SPEAKER_ENROLLMENT",
            List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING),
            NOW
        );
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING),
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
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return findById(tenantId, taskId);
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }
}
