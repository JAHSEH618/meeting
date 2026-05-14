package com.meeting.api.app.task;

import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to {@link WorkerPhaseCompletedEvent} after the callback transaction commits.
 *
 * <ul>
 *   <li>{@code MEETING_FULL_PIPELINE}: open Java LLM phase via {@link TaskStepProgressService#beginJavaPhase}.
 *       Follow-up minutes/extraction services will pick up the {@code JAVA_LLM_RUNNING} task.</li>
 *   <li>Other task types ({@code SPEAKER_ENROLLMENT}, {@code TEXT_EMBEDDING}, {@code RAG_REINDEX}): no
 *       Java-owned step exists, so the task moves directly to {@code TERMINAL} with the worker's terminal status.</li>
 * </ul>
 *
 * Worker callback responses are not blocked by this listener: it fires after the callback transaction commits.
 * Failures here do not roll back the callback; they are logged and surface via task state lag indicators.
 */
@Component
public class WorkerPhaseCompletedListener {
    private static final Logger log = LoggerFactory.getLogger(WorkerPhaseCompletedListener.class);

    public static final String MEETING_FULL_PIPELINE = "MEETING_FULL_PIPELINE";

    private final TaskStepProgressService taskStepProgressService;

    public WorkerPhaseCompletedListener(TaskStepProgressService taskStepProgressService) {
        this.taskStepProgressService = taskStepProgressService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @EventListener
    public void onWorkerPhaseCompleted(WorkerPhaseCompletedEvent event) {
        try {
            if (MEETING_FULL_PIPELINE.equals(event.taskType())) {
                taskStepProgressService.beginJavaPhase(event.tenantId(), event.taskId());
                log.info("worker_phase_completed_started_java_llm task={} tenant={}", event.taskId(), event.tenantId());
                return;
            }
            ProcessingTaskStatus terminal = event.workerStatus();
            taskStepProgressService.completeWithoutJavaPhase(event.tenantId(), event.taskId(), terminal, null);
            log.info("worker_phase_completed_terminal_no_llm task={} tenant={} status={}", event.taskId(), event.tenantId(), terminal);
        } catch (RuntimeException ex) {
            log.warn("worker_phase_completed_listener_failed task={} tenant={} reason={}", event.taskId(), event.tenantId(), ex.getMessage(), ex);
        }
    }
}
