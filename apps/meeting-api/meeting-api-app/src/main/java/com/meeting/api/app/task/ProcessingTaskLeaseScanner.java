package com.meeting.api.app.task;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.OrphanedTaskRepublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessingTaskLeaseScanner {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingTaskLeaseScanner.class);

    private final ProcessingTaskRepository taskRepository;
    private final OrphanedTaskRepublisher republisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;
    private final int batchSize;

    public ProcessingTaskLeaseScanner(
        ProcessingTaskRepository taskRepository,
        OrphanedTaskRepublisher republisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock,
        int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.taskRepository = taskRepository;
        this.republisher = republisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public ScanReport scanOnce(List<String> tenantIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int scanned = 0;
        int orphaned = 0;
        int requeued = 0;
        int cancelled = 0;
        int failed = 0;
        for (String tenantId : tenantIds) {
            List<ProcessingTaskRepository.ExpiredLease> expired = tenantScopedTransaction.execute(
                tenantId,
                "lease-scanner",
                "lease-scan-find-" + tenantId,
                () -> taskRepository.findExpiredLeases(tenantId, now, batchSize)
            );
            scanned += expired.size();
            for (ProcessingTaskRepository.ExpiredLease lease : expired) {
                TransitionResult result = transitionLease(lease, now);
                if (result.orphaned) orphaned += 1;
                if (result.requeued) requeued += 1;
                if (result.cancelled) cancelled += 1;
                if (result.failed) failed += 1;
            }
        }
        return new ScanReport(scanned, orphaned, requeued, cancelled, failed);
    }

    private TransitionResult transitionLease(ProcessingTaskRepository.ExpiredLease lease, OffsetDateTime now) {
        return tenantScopedTransaction.execute(
            lease.tenantId(),
            "lease-scanner",
            "lease-scan-" + lease.taskId(),
            () -> {
                // Claim the row with FOR UPDATE so a concurrent callback (or a
                // second scanner replica) cannot interleave: we either wait for
                // its commit and then re-check the lease/status below, or we hold
                // the lock while transitioning. Without this, a stale save()
                // here could overwrite a just-committed heartbeat/completion and
                // two replicas could both republish the same orphan.
                ProcessingTask task = taskRepository.findByIdForUpdate(lease.tenantId(), lease.taskId()).orElse(null);
                if (task == null) {
                    return new TransitionResult(false, false, false, false);
                }
                String previousLeaseOwner = task.leaseOwner();
                int attemptNo = task.attemptNo();

                // Handle CANCEL_PENDING: confirm cancellation
                if (task.status() == ProcessingTaskStatus.CANCEL_PENDING) {
                    task.confirmCancelled(now);
                    taskRepository.save(task);
                    LOG.info(
                        "cancel_confirmed_on_lease_expiry task={} tenant={} attempt={}",
                        task.taskId(),
                        task.tenantId(),
                        attemptNo
                    );
                    return new TransitionResult(false, false, true, false);
                }

                // Mark orphaned
                boolean orphaned = task.markOrphanedIfLeaseExpired(now);
                if (!orphaned) {
                    return new TransitionResult(false, false, false, false);
                }

                LOG.info(
                    "lease_expired task={} tenant={} attempt={} previousLeaseOwner={}",
                    task.taskId(),
                    task.tenantId(),
                    attemptNo,
                    previousLeaseOwner
                );

                // Bump the attempt (may exhaust the retry budget).
                try {
                    task.requeueOrphaned(now);
                } catch (IllegalStateException e) {
                    if (e.getMessage() != null && e.getMessage().contains("retry exhausted")) {
                        // Max retries exceeded, mark as FAILED
                        task.completeTerminal(
                            ProcessingTaskStatus.FAILED,
                            ErrorCode.TASK_RETRY_EXHAUSTED.name(),
                            now
                        );
                        taskRepository.save(task);
                        LOG.warn(
                            "task_retry_exhausted task={} tenant={} finalAttempt={}",
                            task.taskId(),
                            task.tenantId(),
                            attemptNo
                        );
                        return new TransitionResult(true, false, false, true);
                    }
                    throw e;
                }

                // Re-dispatch the worker message FIRST, then persist the requeue,
                // so the bumped attempt number and the queued message commit
                // together in this transaction. If the original payload can't be
                // recovered, leave the task untouched so the next scan retries.
                boolean republished = republisher.republish(task.tenantId(), task.taskId(), task.attemptNo());
                if (!republished) {
                    LOG.warn(
                        "task_requeue_republish_failed task={} tenant={} newAttempt={} (left for next scan)",
                        task.taskId(),
                        task.tenantId(),
                        task.attemptNo()
                    );
                    return new TransitionResult(true, false, false, false);
                }
                taskRepository.save(task);
                LOG.info(
                    "task_requeued task={} tenant={} newAttempt={}",
                    task.taskId(),
                    task.tenantId(),
                    task.attemptNo()
                );
                return new TransitionResult(true, true, false, false);
            }
        );
    }

    private record TransitionResult(boolean orphaned, boolean requeued, boolean cancelled, boolean failed) {}

    public record ScanReport(int scanned, int orphaned, int requeued, int cancelled, int failed) {}

    /** Convenience for callers that want to scan a single tenant. */
    public ScanReport scanOnce(String tenantId) {
        return scanOnce(new ArrayList<>(List.of(tenantId)));
    }
}
