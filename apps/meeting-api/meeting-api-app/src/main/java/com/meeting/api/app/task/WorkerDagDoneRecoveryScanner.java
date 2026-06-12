package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans for tasks stuck at {@code WORKER_DAG_DONE} phase
 * and re-publishes {@code WorkerPhaseCompletedEvent} to drive them forward.
 *
 * <p>This handles cases where the listener failed to process an event,
 * or where the app restarted after a callback committed but before
 * the listener ran.</p>
 */
@Component
public class WorkerDagDoneRecoveryScanner {
    private static final Logger log = LoggerFactory.getLogger(WorkerDagDoneRecoveryScanner.class);
    private static final int BATCH_SIZE = 100;

    private final ProcessingTaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    public WorkerDagDoneRecoveryScanner(
        ProcessingTaskRepository taskRepository,
        ApplicationEventPublisher eventPublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    /**
     * Runs every minute. Scans for tasks at {@code WORKER_DAG_DONE}
     * phase that are not in a terminal status, and re-publishes
     * {@code WorkerPhaseCompletedEvent} for each.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void scanAndRecover() {
        try {
            List<ProcessingTask> stuckTasks = findStuckTasks();
            if (stuckTasks.isEmpty()) {
                return;
            }
            log.info("worker_dag_done_recovery_scan found={} tasks", stuckTasks.size());
            int recovered = 0;
            for (ProcessingTask task : stuckTasks) {
                try {
                    republishEvent(task);
                    recovered++;
                } catch (RuntimeException ex) {
                    log.warn(
                        "worker_dag_done_recovery_failed task={} tenant={} reason={}",
                        task.taskId(), task.tenantId(), ex.getMessage(), ex
                    );
                }
            }
            log.info("worker_dag_done_recovery_completed scanned={} recovered={}", stuckTasks.size(), recovered);
        } catch (RuntimeException ex) {
            log.error("worker_dag_done_recovery_scan_failed reason={}", ex.getMessage(), ex);
        }
    }

    private List<ProcessingTask> findStuckTasks() {
        // We need to search across all tenants, so we can't use TenantScopedTransaction here
        // The repository implementation should handle multi-tenant query
        if (taskRepository instanceof ProcessingTaskRepositoryExtensions ext) {
            return ext.findStuckWorkerDagDone(BATCH_SIZE);
        }
        // Fallback: no extension method available
        return List.of();
    }

    private void republishEvent(ProcessingTask task) {
        tenantScopedTransaction.executeWithoutResult(
            task.tenantId(), null, null,
            () -> {
                // For recovery, we reconstruct a minimal event with empty steps/artifacts
                // The listener will re-read the task state and drive it forward
                WorkerPhaseCompletedEvent event = new WorkerPhaseCompletedEvent(
                    java.util.UUID.randomUUID().toString(),
                    task.tenantId(),
                    task.taskId(),
                    task.taskType(),
                    task.attemptNo(),
                    task.status(),
                    List.of(), // completedSteps: not tracked in ProcessingTask
                    List.of(), // skippedSteps: not tracked in ProcessingTask
                    null,      // artifactManifestId: not tracked in ProcessingTask
                    0L,        // sequenceNo not relevant for recovery
                    OffsetDateTime.now(clock)
                );
                eventPublisher.publishEvent(event);
                log.info(
                    "worker_dag_done_recovery_republished task={} tenant={} status={}",
                    task.taskId(), task.tenantId(), task.status()
                );
            }
        );
    }

    /**
     * Extension interface for ProcessingTaskRepository to support
     * recovery queries. Not all implementations need to support this.
     */
    public interface ProcessingTaskRepositoryExtensions {
        /**
         * Finds tasks stuck at WORKER_DAG_DONE phase (non-terminal status).
         * Query should scan across all tenants.
         */
        List<ProcessingTask> findStuckWorkerDagDone(int limit);
    }
}
