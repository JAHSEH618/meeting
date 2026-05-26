package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Java-owned step orchestrator for the {@link ProcessingTaskPhase#JAVA_LLM_RUNNING} phase.
 * Updates carry {@link com.meeting.api.client.enums.ProcessingStepUpdateSource#JAVA_TASK_SERVICE}.
 * Heartbeats and worker-callback paths must NOT route through this service.
 */
@Service
public class TaskStepProgressService {
    private final ProcessingTaskRepository taskRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    @Autowired
    public TaskStepProgressService(
        ProcessingTaskRepository taskRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(taskRepository, tenantScopedTransaction, Clock.systemUTC());
    }
    public TaskStepProgressService(
        ProcessingTaskRepository taskRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }
    public ProcessingTaskDTO beginJavaPhase(String tenantId, String taskId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            ProcessingTask task = load(tenantId, taskId);
            if (task.phase() == ProcessingTaskPhase.JAVA_LLM_RUNNING || task.phase() == ProcessingTaskPhase.TERMINAL) {
                return ProcessingTaskAssembler.toDto(task);
            }
            task.beginJavaLlm(OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }
    public ProcessingTaskDTO markStepRunning(String tenantId, String taskId, ProcessingStep stepName, int progress) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            ProcessingTask task = load(tenantId, taskId);
            task.markJavaStepRunning(stepName, progress, OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }
    public ProcessingTaskDTO markStepSucceeded(String tenantId, String taskId, ProcessingStep stepName) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            ProcessingTask task = load(tenantId, taskId);
            task.markJavaStepSucceeded(stepName, OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }
    public ProcessingTaskDTO markStepFailed(String tenantId, String taskId, ProcessingStep stepName, String errorCode) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            ProcessingTask task = load(tenantId, taskId);
            task.markJavaStepFailed(stepName, errorCode, OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }
    public ProcessingTaskDTO completeJavaPhase(String tenantId, String taskId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            ProcessingTask task = load(tenantId, taskId);
            if (task.phase() == ProcessingTaskPhase.TERMINAL) {
                return ProcessingTaskAssembler.toDto(task);
            }
            task.completeJavaPhase(OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    /**
     * Transition directly to TERMINAL without entering Java LLM phase.
     * Used for task types that don't have Java-owned steps (TEXT_EMBEDDING / RAG_REINDEX / SPEAKER_ENROLLMENT).
     */
    public ProcessingTaskDTO completeWithoutJavaPhase(String tenantId, String taskId, ProcessingTaskStatus terminalStatus, String errorCode) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            ProcessingTask task = load(tenantId, taskId);
            if (task.phase() == ProcessingTaskPhase.TERMINAL) {
                return ProcessingTaskAssembler.toDto(task);
            }
            if (task.hasPendingJavaSteps()) {
                throw new IllegalStateException("task has pending Java steps; use completeJavaPhase instead");
            }
            task.completeTerminal(terminalStatus, errorCode, OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    private ProcessingTask load(String tenantId, String taskId) {
        return taskRepository.findById(tenantId, taskId)
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
    }
}
