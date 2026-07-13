package com.meeting.api.start.config;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.WorkerDagDoneRecoveryScanner;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Schedules {@link WorkerDagDoneRecoveryScanner}: the safety net that
 * re-publishes {@code WorkerPhaseCompletedEvent} for tasks stuck at
 * {@code WORKER_DAG_DONE} (listener crash, restart between callback
 * commit and listener run). Task tables are FORCE RLS, so the scan runs
 * once per active tenant inside a tenant-scoped transaction, mirroring
 * the other scanners.
 *
 * <p>Disable with {@code meeting.worker-dag-recovery.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "meeting.worker-dag-recovery",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class WorkerDagDoneRecoveryScannerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerDagDoneRecoveryScannerConfig.class);

    private final WorkerDagDoneRecoveryScanner scanner;
    private final List<String> tenantIds;

    public WorkerDagDoneRecoveryScannerConfig(
        ProcessingTaskRepository taskRepository,
        ApplicationEventPublisher eventPublisher,
        TenantScopedTransaction tenantScopedTransaction,
        @Value("${meeting.worker-dag-recovery.batch-size:100}") int batchSize,
        @Value("${meeting.worker-dag-recovery.stuck-after-ms:120000}") long stuckAfterMs,
        @Value("${meeting.tenants.active:${meeting.worker-dag-recovery.tenants:tenant_default}}")
            String tenantIdsCsv
    ) {
        this.scanner = new WorkerDagDoneRecoveryScanner(
            taskRepository,
            eventPublisher,
            tenantScopedTransaction,
            Clock.systemUTC(),
            batchSize,
            Duration.ofMillis(stuckAfterMs)
        );
        this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
    }

    @Bean
    public WorkerDagDoneRecoveryScanner workerDagDoneRecoveryScanner() {
        return scanner;
    }

    @Scheduled(
        fixedDelayString = "${meeting.worker-dag-recovery.interval-ms:60000}",
        initialDelayString = "${meeting.worker-dag-recovery.initial-delay-ms:60000}"
    )
    public void scanStuckWorkerDagDoneTasks() {
        if (tenantIds.isEmpty()) return;
        try {
            WorkerDagDoneRecoveryScanner.ScanReport report = scanner.scanAndRecover(tenantIds);
            if (report.scanned() > 0) {
                LOG.info(
                    "worker_dag_done_recovery_run scanned={} recovered={}",
                    report.scanned(), report.recovered()
                );
            }
        } catch (RuntimeException cause) {
            LOG.warn("worker_dag_done_recovery_run_failed", cause);
        }
    }
}
