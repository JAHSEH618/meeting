package com.meeting.api.app.task;

import com.meeting.api.app.speaker.SpeakerAutoConfirmService;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to {@link WorkerPhaseCompletedEvent} after the callback transaction commits.
 *
 * <ul>
 *   <li>{@code MEETING_FULL_PIPELINE}: run the Java LLM phase
 *       unless the task was created with {@code hold_at_worker_phase=true} (workstation D3 gate),
 *       in which case the task stays at {@code WORKER_DAG_DONE} awaiting an explicit
 *       {@code resume-java-phase} call from the user.</li>
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
    private final ProcessingTaskRepository taskRepository;
    private final JavaLlmPhaseOrchestrator javaLlmPhaseOrchestrator;
    private final SpeakerAutoConfirmService speakerAutoConfirmService;

    public WorkerPhaseCompletedListener(
        TaskStepProgressService taskStepProgressService,
        ProcessingTaskRepository taskRepository
    ) {
        this(taskStepProgressService, taskRepository, null, null);
    }

    public WorkerPhaseCompletedListener(
        TaskStepProgressService taskStepProgressService,
        ProcessingTaskRepository taskRepository,
        JavaLlmPhaseOrchestrator javaLlmPhaseOrchestrator
    ) {
        this(taskStepProgressService, taskRepository, javaLlmPhaseOrchestrator, null);
    }

    @Autowired
    public WorkerPhaseCompletedListener(
        TaskStepProgressService taskStepProgressService,
        ProcessingTaskRepository taskRepository,
        JavaLlmPhaseOrchestrator javaLlmPhaseOrchestrator,
        SpeakerAutoConfirmService speakerAutoConfirmService
    ) {
        this.taskStepProgressService = taskStepProgressService;
        this.taskRepository = taskRepository;
        this.javaLlmPhaseOrchestrator = javaLlmPhaseOrchestrator;
        this.speakerAutoConfirmService = speakerAutoConfirmService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkerPhaseCompleted(WorkerPhaseCompletedEvent event) {
        try {
            if (MEETING_FULL_PIPELINE.equals(event.taskType())) {
                Optional<ProcessingTask> taskOpt = taskRepository.findById(event.tenantId(), event.taskId());
                if (taskOpt.isPresent() && taskOpt.get().holdAtWorkerPhase()) {
                    log.info(
                        "worker_phase_completed_held task={} tenant={} waiting_for_resume",
                        event.taskId(), event.tenantId()
                    );
                    return;
                }
                autoConfirmSpeakers(event);
                if (javaLlmPhaseOrchestrator != null) {
                    javaLlmPhaseOrchestrator.run(event.tenantId(), event.taskId());
                } else {
                    taskStepProgressService.beginJavaPhase(event.tenantId(), event.taskId());
                }
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

    private void autoConfirmSpeakers(WorkerPhaseCompletedEvent event) {
        if (speakerAutoConfirmService == null) {
            return;
        }
        try {
            speakerAutoConfirmService.autoConfirmAboveThreshold(event.tenantId(), event.taskId());
        } catch (RuntimeException ex) {
            log.warn(
                "speaker_auto_confirm_listener_failed task={} tenant={} reason={}",
                event.taskId(), event.tenantId(), ex.getMessage(), ex
            );
        }
    }
}
