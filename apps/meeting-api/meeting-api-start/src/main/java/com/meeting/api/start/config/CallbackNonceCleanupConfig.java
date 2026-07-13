package com.meeting.api.start.config;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.domain.task.CallbackNonceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically deletes expired rows from {@code callback_nonces}.
 *
 * <p>Every worker callback — including each 15s step heartbeat — records a
 * nonce with a 5-minute TTL, so the table grows at heartbeat rate and, without
 * this job, is never reclaimed: index bloat, autovacuum pressure and a slowly
 * degrading hot path ({@code exists}/{@code record} on every callback). The
 * table is FORCE RLS, so cleanup runs once per active tenant inside a
 * tenant-scoped transaction, mirroring the other scanners.
 *
 * <p>Disable with {@code meeting.callback-nonce-cleanup.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "meeting.callback-nonce-cleanup",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class CallbackNonceCleanupConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackNonceCleanupConfig.class);

    private final CallbackNonceRepository nonceRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;
    private final List<String> tenantIds;

    public CallbackNonceCleanupConfig(
        CallbackNonceRepository nonceRepository,
        TenantScopedTransaction tenantScopedTransaction,
        @Value("${meeting.tenants.active:${meeting.callback-nonce-cleanup.tenants:tenant_default}}")
            String tenantIdsCsv
    ) {
        this.nonceRepository = nonceRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = Clock.systemUTC();
        this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
    }

    @Scheduled(
        fixedDelayString = "${meeting.callback-nonce-cleanup.interval-ms:300000}",
        initialDelayString = "${meeting.callback-nonce-cleanup.initial-delay-ms:120000}"
    )
    public void cleanupExpiredNonces() {
        if (tenantIds.isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        int total = 0;
        for (String tenantId : tenantIds) {
            try {
                total += tenantScopedTransaction.execute(
                    tenantId,
                    "nonce-cleanup",
                    "nonce-cleanup-" + tenantId,
                    () -> nonceRepository.cleanupExpired(now)
                );
            } catch (RuntimeException cause) {
                LOG.warn("callback_nonce_cleanup_failed tenant={}", tenantId, cause);
            }
        }
        if (total > 0) {
            LOG.info("callback_nonce_cleanup_run deleted={}", total);
        }
    }
}
