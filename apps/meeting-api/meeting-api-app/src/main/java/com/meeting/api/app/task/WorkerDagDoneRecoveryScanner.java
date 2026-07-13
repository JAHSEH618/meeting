package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Scans for tasks stuck at {@code WORKER_DAG_DONE} phase and re-publishes
 * {@code WorkerPhaseCompletedEvent} to drive them forward.
 *
 * <p>This handles cases where the listener failed to process an event,
 * or where the app restarted after a callback committed but before
 * the listener ran.</p>
 *
 * <p>All task tables are FORCE RLS, so the scan iterates the configured
 * active tenants and runs each query inside a tenant-scoped transaction
 * — a naive cross-tenant query would silently return nothing. Only tasks
 * whose last update is older than {@code stuckAfter} are recovered, so
 * the scanner does not race the asynchronous
 * {@code WorkerPhaseCompletedListener} that normally fires right after
 * the callback commits.</p>
 *
 * <p>Scheduling and tenant configuration live in the start module
 * ({@code WorkerDagDoneRecoveryScannerConfig}), mirroring the other
 * scanners.</p>
 */
public class WorkerDagDoneRecoveryScanner {
    private static final Logger log = LoggerFactory.getLogger(WorkerDagDoneRecoveryScanner.class);

    private final ProcessingTaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;
    private final int batchSize;
    private final Duration stuckAfter;
    private volatile boolean warnedMissingExtension;

    public WorkerDagDoneRecoveryScanner(
        ProcessingTaskRepository taskRepository,
        ApplicationEventPublisher eventPublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock,
        int batchSize,
        Duration stuckAfter
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (stuckAfter.isNegative()) {
            throw new IllegalArgumentException("stuckAfter must not be negative");
        }
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
        this.batchSize = batchSize;
        this.stuckAfter = stuckAfter;
    }

    /**
     * Scans the given tenants for tasks at {@code WORKER_DAG_DONE} phase
     * that are not in a terminal status and have been sitting there for at
     * least {@code stuckAfter}, and re-publishes
     * {@code WorkerPhaseCompletedEvent} for each.
     */
    public ScanReport scanAndRecover(List<String> tenantIds) {
        if (!(taskRepository instanceof ProcessingTaskRepositoryExtensions ext)) {
            // The safety net is dead without the recovery query — say so loudly
            // (once) instead of silently scanning nothing.
            if (!warnedMissingExtension) {
                warnedMissingExtension = true;
                log.warn(
                    "worker_dag_done_recovery_unsupported repository={} does not implement"
                        + " ProcessingTaskRepositoryExtensions — stuck WORKER_DAG_DONE tasks"
                        + " will NOT be recovered",
                    taskRepository.getClass().getName()
                );
            }
            return new ScanReport(0, 0);
        }
        OffsetDateTime olderThan = OffsetDateTime.now(clock).minus(stuckAfter);
        int scanned = 0;
        int recovered = 0;
        for (String tenantId : tenantIds) {
            List<ProcessingTask> stuckTasks;
            try {
                stuckTasks = tenantScopedTransaction.execute(
                    tenantId,
                    "worker-dag-done-recovery",
                    "dag-done-recovery-find-" + tenantId,
                    () -> ext.findStuckWorkerDagDone(tenantId, olderThan, batchSize)
                );
            } catch (RuntimeException ex) {
                log.warn(
                    "worker_dag_done_recovery_scan_failed tenant={} reason={}",
                    tenantId, ex.getMessage(), ex
                );
                continue;
            }
            if (stuckTasks.isEmpty()) {
                continue;
            }
            log.info("worker_dag_done_recovery_scan tenant={} found={} tasks", tenantId, stuckTasks.size());
            scanned += stuckTasks.size();
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
        }
        if (scanned > 0) {
            log.info("worker_dag_done_recovery_completed scanned={} recovered={}", scanned, recovered);
        }
        return new ScanReport(scanned, recovered);
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

    public record ScanReport(int scanned, int recovered) {}

    /**
     * Extension interface for ProcessingTaskRepository to support
     * recovery queries. The query is tenant-scoped: task tables are FORCE
     * RLS, so callers must invoke it once per active tenant inside a
     * tenant-scoped transaction.
     */
    public interface ProcessingTaskRepositoryExtensions {
        /**
         * Finds tasks of the given tenant stuck at WORKER_DAG_DONE phase
         * (non-terminal status, not held at the worker phase) whose last
         * update is older than {@code olderThan}.
         */
        List<ProcessingTask> findStuckWorkerDagDone(String tenantId, OffsetDateTime olderThan, int limit);
    }
}
