package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
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

    public ScanReport scanOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ProcessingTaskRepository.ExpiredLease> expired = taskRepository.findExpiredLeases(now, batchSize);
        if (expired.isEmpty()) {
            return new ScanReport(0, 0);
        }
        int orphaned = 0;
        for (ProcessingTaskRepository.ExpiredLease lease : expired) {
            boolean changed = tenantScopedTransaction.execute(
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
            if (changed) {
                orphaned += 1;
            }
        }
        return new ScanReport(expired.size(), orphaned);
    }

    public record ScanReport(int scanned, int orphaned) {}
}
