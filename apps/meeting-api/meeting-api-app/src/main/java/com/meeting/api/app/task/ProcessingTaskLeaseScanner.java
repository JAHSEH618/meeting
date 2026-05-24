package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
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
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;
    private final int batchSize;

    public ProcessingTaskLeaseScanner(
        ProcessingTaskRepository taskRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock,
        int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.taskRepository = taskRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public ScanReport scanOnce(List<String> tenantIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int scanned = 0;
        int orphaned = 0;
        for (String tenantId : tenantIds) {
            List<ProcessingTaskRepository.ExpiredLease> expired = tenantScopedTransaction.execute(
                tenantId,
                "lease-scanner",
                "lease-scan-find-" + tenantId,
                () -> taskRepository.findExpiredLeases(tenantId, now, batchSize)
            );
            scanned += expired.size();
            for (ProcessingTaskRepository.ExpiredLease lease : expired) {
                boolean changed = transitionLease(lease, now);
                if (changed) {
                    orphaned += 1;
                }
            }
        }
        return new ScanReport(scanned, orphaned);
    }

    private boolean transitionLease(ProcessingTaskRepository.ExpiredLease lease, OffsetDateTime now) {
        return tenantScopedTransaction.execute(
            lease.tenantId(),
            "lease-scanner",
            "lease-scan-" + lease.taskId(),
            () -> {
                ProcessingTask task = taskRepository.findById(lease.tenantId(), lease.taskId()).orElse(null);
                if (task == null) {
                    return false;
                }
                String previousLeaseOwner = task.leaseOwner();
                boolean transitioned = task.markOrphanedIfLeaseExpired(now);
                if (transitioned) {
                    taskRepository.save(task);
                    LOG.info(
                        "lease_expired task={} tenant={} attempt={} previousLeaseOwner={}",
                        task.taskId(),
                        task.tenantId(),
                        task.attemptNo(),
                        previousLeaseOwner
                    );
                }
                return transitioned;
            }
        );
    }

    public record ScanReport(int scanned, int orphaned) {}

    /** Convenience for callers that want to scan a single tenant. */
    public ScanReport scanOnce(String tenantId) {
        return scanOnce(new ArrayList<>(List.of(tenantId)));
    }
}
