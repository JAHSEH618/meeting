package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.extraction.ExtractionApplicationService;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.task.JavaLlmPhaseOrchestrator;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.extraction.ExtractionSummary;
import com.meeting.api.client.minutes.MinutesDTO;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaLlmPhaseOrchestratorTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-20T09:00:00Z");

    @Test
    void runsSummaryAndExtractionThenCompletesTerminal() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(workerDagDoneTask());
        RecordingMinutesService minutes = new RecordingMinutesService();
        RecordingExtractionService extraction = new RecordingExtractionService();
        JavaLlmPhaseOrchestrator orchestrator = orchestrator(tasks, minutes, extraction);

        var dto = orchestrator.run("tenant_01", "task_01");

        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.SUCCEEDED);
        assertThat(tasks.task.step(ProcessingStep.SUMMARY).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(tasks.task.step(ProcessingStep.EXTRACTION).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(minutes.calls).isEqualTo(1);
        assertThat(minutes.lastTaskId).isEqualTo("task_01");
        assertThat(extraction.calls).isEqualTo(1);
        assertThat(extraction.lastMeetingId).isEqualTo("meeting_01");
    }

    @Test
    void skipsAlreadySucceededStepsForIdempotentResume() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(javaLlmRunningTask());
        tasks.task.markJavaStepSucceeded(ProcessingStep.SUMMARY, NOW.plusSeconds(1));
        tasks.task.markJavaStepSucceeded(ProcessingStep.EXTRACTION, NOW.plusSeconds(2));
        RecordingMinutesService minutes = new RecordingMinutesService();
        RecordingExtractionService extraction = new RecordingExtractionService();
        JavaLlmPhaseOrchestrator orchestrator = orchestrator(tasks, minutes, extraction);

        var dto = orchestrator.run("tenant_01", "task_01");

        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(minutes.calls).isZero();
        assertThat(extraction.calls).isZero();
    }

    @Test
    void summaryFailureMarksSummaryAndExtractionFailedThenClosesTerminal() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(workerDagDoneTask());
        RecordingMinutesService minutes = new RecordingMinutesService();
        RecordingExtractionService extraction = new RecordingExtractionService();
        minutes.failure = new IllegalStateException("llm outage");
        JavaLlmPhaseOrchestrator orchestrator = orchestrator(tasks, minutes, extraction);

        assertThatThrownBy(() -> orchestrator.run("tenant_01", "task_01"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("llm outage");

        assertThat(tasks.task.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(tasks.task.status()).isEqualTo(ProcessingTaskStatus.FAILED);
        assertThat(tasks.task.step(ProcessingStep.SUMMARY).status()).isEqualTo(StepStatus.FAILED);
        assertThat(tasks.task.step(ProcessingStep.EXTRACTION).status()).isEqualTo(StepStatus.FAILED);
        assertThat(extraction.calls).isZero();
    }

    @Test
    void extractionFailureKeepsGeneratedMinutesAsPartialSuccess() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(workerDagDoneTask());
        RecordingMinutesService minutes = new RecordingMinutesService();
        RecordingExtractionService extraction = new RecordingExtractionService();
        extraction.failure = new IllegalStateException("extract outage");
        JavaLlmPhaseOrchestrator orchestrator = orchestrator(tasks, minutes, extraction);

        var dto = orchestrator.run("tenant_01", "task_01");

        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.PARTIAL_SUCCEEDED);
        assertThat(dto.lastErrorCode()).isEqualTo("JAVA_LLM_PHASE_FAILED");
        assertThat(tasks.task.step(ProcessingStep.SUMMARY).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(tasks.task.step(ProcessingStep.EXTRACTION).status()).isEqualTo(StepStatus.FAILED);
    }

    private static JavaLlmPhaseOrchestrator orchestrator(
        InMemoryTaskRepository tasks,
        MinutesApplicationService minutes,
        ExtractionApplicationService extraction
    ) {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        return new JavaLlmPhaseOrchestrator(
            new TaskStepProgressService(tasks, TenantScopedTransaction.immediate(), clock),
            tasks,
            minutes,
            extraction
        );
    }

    private static ProcessingTask workerDagDoneTask() {
        ProcessingTask task = workerRunningTask();
        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR),
            List.<WorkerPhaseCompletedEvent.SkippedStep>of(),
            1,
            "worker_01:task_01:1",
            NOW.plusSeconds(1)
        );
        return task;
    }

    private static ProcessingTask javaLlmRunningTask() {
        ProcessingTask task = workerDagDoneTask();
        task.beginJavaLlm(NOW.plusSeconds(2));
        return task;
    }

    private static ProcessingTask workerRunningTask() {
        ProcessingTask task = ProcessingTask.create(
            "task_01",
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            List.of(
                ProcessingStep.AUDIO_UPLOAD,
                ProcessingStep.AUDIO_PREPROCESS,
                ProcessingStep.ASR,
                ProcessingStep.SUMMARY,
                ProcessingStep.EXTRACTION
            ),
            NOW,
            true
        );
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        return task;
    }

    private static final class RecordingMinutesService extends MinutesApplicationService {
        private int calls;
        private String lastTaskId;
        private RuntimeException failure;

        private RecordingMinutesService() {
            super(null, null, null, null, TenantScopedTransaction.immediate(), new ObjectMapper());
        }

        @Override
        public MinutesDTO generateForTask(String tenantId, String meetingId, String taskId, Integer expectedTranscriptVersion) {
            calls++;
            lastTaskId = taskId;
            if (failure != null) {
                throw failure;
            }
            return null;
        }
    }

    private static final class RecordingExtractionService extends ExtractionApplicationService {
        private int calls;
        private String lastMeetingId;
        private RuntimeException failure;

        private RecordingExtractionService() {
            super(null, null, null, null, null, null, TenantScopedTransaction.immediate(), new ObjectMapper());
        }

        @Override
        public ExtractionSummary extractForTask(String tenantId, String meetingId, String taskId) {
            calls++;
            lastMeetingId = meetingId;
            if (failure != null) {
                throw failure;
            }
            return null;
        }
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
            return task != null && tenantId.equals(task.tenantId()) && taskId.equals(task.taskId())
                ? Optional.of(task) : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return task != null && tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId())
                ? Optional.of(task) : Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }
}
