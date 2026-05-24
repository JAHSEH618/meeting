package com.meeting.api.start.config;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.app.task.ProcessingTaskLeaseScanner;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "meeting.lease-scanner", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProcessingTaskLeaseScannerConfig {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingTaskLeaseScannerConfig.class);

    private final ProcessingTaskLeaseScanner scanner;
    private final MeetingApiMetrics metrics;
    private final List<String> tenantIds;

    public ProcessingTaskLeaseScannerConfig(
        ProcessingTaskRepository taskRepository,
        TenantScopedTransaction tenantScopedTransaction,
        MeetingApiMetrics metrics,
        @Value("${meeting.lease-scanner.batch-size:50}") int batchSize,
        @Value("${meeting.tenants.active:${meeting.lease-scanner.tenants:tenant_default}}")
            String tenantIdsCsv
    ) {
        this.scanner = new ProcessingTaskLeaseScanner(
            taskRepository,
            tenantScopedTransaction,
            Clock.systemUTC(),
            batchSize
        );
        this.metrics = metrics;
        this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
    }

    @Bean
    public ProcessingTaskLeaseScanner processingTaskLeaseScanner() {
        return scanner;
    }

    @Scheduled(fixedDelayString = "${meeting.lease-scanner.interval-ms:30000}", initialDelayString = "${meeting.lease-scanner.initial-delay-ms:30000}")
    public void scanExpiredLeases() {
        if (tenantIds.isEmpty()) return;
        try {
            metrics.leaseScannerRunCounter().increment();
            ProcessingTaskLeaseScanner.ScanReport report = scanner.scanOnce(tenantIds);
            if (report.orphaned() > 0) {
                metrics.leaseScannerOrphanedCounter().increment(report.orphaned());
            }
            if (report.scanned() > 0) {
                LOG.info("lease_scanner_run scanned={} orphaned={}", report.scanned(), report.orphaned());
            }
        } catch (RuntimeException cause) {
            LOG.warn("lease_scanner_failed", cause);
        }
    }
}
