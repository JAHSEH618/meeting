package com.meeting.api.app.task;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.extraction.ExtractionApplicationService;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.rag.TranscriptIndexFallbackEvent;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Synchronous Java-owned LLM phase runner.
 *
 * <p>Both the default worker-complete path and workstation's explicit
 * resume-java-phase path use this class so SUMMARY / EXTRACTION step state,
 * minutes persistence, and downstream RAG reindexing stay consistent.</p>
 *
 * <p>Callers arrive here without an ambient transaction (async
 * {@code WorkerPhaseCompletedListener}, or {@code resumeJavaPhase} after its
 * gating transaction has committed), so every task read goes through
 * {@link TenantScopedTransaction} — a bare repository call would see empty
 * results under RLS. The LLM invocations themselves
 * ({@code generateForTask} / {@code extractForTask}) deliberately stay
 * OUTSIDE any database transaction: those services manage their own
 * "short TX / no-TX LLM / short TX" split so a slow provider never holds
 * a connection or an open transaction.</p>
 */
@Service
public class JavaLlmPhaseOrchestrator {
    private final TaskStepProgressService taskStepProgressService;
    private final ProcessingTaskRepository taskRepository;
    private final MinutesApplicationService minutesApplicationService;
    private final ExtractionApplicationService extractionApplicationService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TenantScopedTransaction tenantScopedTransaction;

    public JavaLlmPhaseOrchestrator(
        TaskStepProgressService taskStepProgressService,
        ProcessingTaskRepository taskRepository,
        MinutesApplicationService minutesApplicationService,
        ExtractionApplicationService extractionApplicationService,
        ApplicationEventPublisher applicationEventPublisher,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this.taskStepProgressService = taskStepProgressService;
        this.taskRepository = taskRepository;
        this.minutesApplicationService = minutesApplicationService;
        this.extractionApplicationService = extractionApplicationService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
    }

    public ProcessingTaskDTO run(String tenantId, String taskId) {
        ProcessingTask task = load(tenantId, taskId);
        if (task.phase() == ProcessingTaskPhase.TERMINAL) {
            return ProcessingTaskAssembler.toDto(task);
        }
        if (task.phase() == ProcessingTaskPhase.WORKER_DAG_DONE) {
            taskStepProgressService.beginJavaPhase(tenantId, taskId);
            task = load(tenantId, taskId);
        }
        if (task.phase() != ProcessingTaskPhase.JAVA_LLM_RUNNING) {
            throw new ApplicationException(
                ErrorCode.INVALID_TASK_PHASE, 422,
                "task phase is " + task.phase() + ", expected WORKER_DAG_DONE or JAVA_LLM_RUNNING", false
            );
        }
        if (task.meetingId() == null || task.meetingId().isBlank()) {
            throw new ApplicationException(
                ErrorCode.INVALID_TASK_MESSAGE, 422,
                "Java LLM phase requires a meeting-bound task", false
            );
        }

        runSummaryIfNeeded(tenantId, taskId, task.meetingId());
        runExtractionIfNeeded(tenantId, taskId, task.meetingId());
        return taskStepProgressService.completeJavaPhase(tenantId, taskId);
    }

    private void runSummaryIfNeeded(String tenantId, String taskId, String meetingId) {
        ProcessingTask task = load(tenantId, taskId);
        if (isSucceeded(task, ProcessingStep.SUMMARY)) {
            return;
        }
        taskStepProgressService.markStepRunning(tenantId, taskId, ProcessingStep.SUMMARY, 10);
        try {
            minutesApplicationService.generateForTask(tenantId, meetingId, taskId, null);
            taskStepProgressService.markStepSucceeded(tenantId, taskId, ProcessingStep.SUMMARY);
        } catch (RuntimeException ex) {
            taskStepProgressService.markStepFailed(tenantId, taskId, ProcessingStep.SUMMARY, errorCode(ex));
            markExtractionFailedIfPending(tenantId, taskId);
            taskStepProgressService.completeJavaPhase(tenantId, taskId);
            // Minutes never generated, so MinutesGeneratedRagIndexer won't index
            // the transcript. Fire a best-effort fallback to index it directly,
            // after the failure transitions above have committed.
            applicationEventPublisher.publishEvent(new TranscriptIndexFallbackEvent(tenantId, meetingId));
            throw ex;
        }
    }

    private void runExtractionIfNeeded(String tenantId, String taskId, String meetingId) {
        ProcessingTask task = load(tenantId, taskId);
        if (isSucceeded(task, ProcessingStep.EXTRACTION)) {
            return;
        }
        taskStepProgressService.markStepRunning(tenantId, taskId, ProcessingStep.EXTRACTION, 10);
        try {
            extractionApplicationService.extractForTask(tenantId, meetingId, taskId);
            taskStepProgressService.markStepSucceeded(tenantId, taskId, ProcessingStep.EXTRACTION);
        } catch (RuntimeException ex) {
            taskStepProgressService.markStepFailed(tenantId, taskId, ProcessingStep.EXTRACTION, errorCode(ex));
        }
    }

    private void markExtractionFailedIfPending(String tenantId, String taskId) {
        ProcessingTask task = load(tenantId, taskId);
        try {
            StepStatus status = task.step(ProcessingStep.EXTRACTION).status();
            if (status == StepStatus.PENDING || status == StepStatus.QUEUED || status == StepStatus.RUNNING) {
                taskStepProgressService.markStepFailed(tenantId, taskId, ProcessingStep.EXTRACTION, "UPSTREAM_STEP_FAILED");
            }
        } catch (IllegalArgumentException ignored) {
            // Some legacy task shapes do not include EXTRACTION.
        }
    }

    private ProcessingTask load(String tenantId, String taskId) {
        // Read inside a tenant-scoped transaction: this runs on async / post-commit
        // paths where no transaction (and no tenant GUC) is active, and RLS would
        // otherwise return empty and misreport the task as TASK_NOT_FOUND.
        return tenantScopedTransaction.execute(tenantId, null, null,
                () -> taskRepository.findById(tenantId, taskId))
            .orElseThrow(() -> new ApplicationException(
                ErrorCode.TASK_NOT_FOUND, 404,
                "task not found: " + taskId, false
            ));
    }

    private static boolean isSucceeded(ProcessingTask task, ProcessingStep stepName) {
        try {
            return task.step(stepName).status() == StepStatus.SUCCEEDED;
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    private static String errorCode(RuntimeException ex) {
        if (ex instanceof ApplicationException appEx) {
            return appEx.errorCode().name();
        }
        if (ex instanceof LlmProviderException llmEx) {
            return llmEx.errorCode().name();
        }
        return "JAVA_LLM_PHASE_FAILED";
    }
}
