package com.meeting.api.start.config;

import com.meeting.api.app.breakglass.BreakGlassExpiryScanner;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.domain.breakglass.BreakGlassRequestRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wires {@link BreakGlassExpiryScanner} into the Spring scheduler.
 * Disabled when {@code meeting.break-glass.scanner.enabled=false}
 * (useful in tests + locally with a single-tenant fixture).
 */
@Configuration
@ConditionalOnProperty(
    prefix = "meeting.break-glass.scanner",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class BreakGlassExpiryScannerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassExpiryScannerConfig.class);

    private final BreakGlassExpiryScanner scanner;
    private final List<String> tenantIds;

    public BreakGlassExpiryScannerConfig(
        BreakGlassRequestRepository repo,
        TenantScopedTransaction tenantTx,
        @Value("${meeting.break-glass.scanner.batch-size:50}") int batchSize,
        @Value("${meeting.tenants.active:${meeting.break-glass.scanner.tenants:tenant_default}}")
            String tenantIdsCsv
    ) {
        this.scanner = new BreakGlassExpiryScanner(repo, tenantTx, Clock.systemUTC(), batchSize);
        this.tenantIds = List.of(tenantIdsCsv.split(",")).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    @Bean
    public BreakGlassExpiryScanner breakGlassExpiryScanner() {
        return scanner;
    }

    @Scheduled(
        fixedDelayString = "${meeting.break-glass.scanner.interval-ms:300000}",
        initialDelayString = "${meeting.break-glass.scanner.initial-delay-ms:60000}"
    )
    public void scan() {
        if (tenantIds.isEmpty()) return;
        try {
            BreakGlassExpiryScanner.ScanReport report = scanner.scanOnce(tenantIds);
            if (report.expired() > 0) {
                LOG.info(
                    "break_glass_scanner_run claimed={} expired={}",
                    report.claimed(), report.expired()
                );
            }
        } catch (RuntimeException ex) {
            LOG.warn("break_glass_scanner_failed", ex);
        }
    }
}
