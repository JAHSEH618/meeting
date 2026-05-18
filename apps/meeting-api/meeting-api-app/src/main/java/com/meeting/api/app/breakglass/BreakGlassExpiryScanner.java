package com.meeting.api.app.breakglass;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.domain.breakglass.BreakGlassRequest;
import com.meeting.api.domain.breakglass.BreakGlassRequestRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives APPROVED break-glass requests past their {@code valid_until}
 * into {@link com.meeting.api.client.enums.BreakGlassStatus#EXPIRED}.
 *
 * <p>This is the read-time safety net: every {@code isActiveAt(now)}
 * check on the aggregate already rejects late accesses, but
 * surfacing EXPIRED in the admin UI requires actually persisting the
 * transition. Scheduled to run every 5 minutes by default.
 */
public class BreakGlassExpiryScanner {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassExpiryScanner.class);

    private final BreakGlassRequestRepository repo;
    private final TenantScopedTransaction tenantTx;
    private final Clock clock;
    private final int batchSize;

    public BreakGlassExpiryScanner(
        BreakGlassRequestRepository repo,
        TenantScopedTransaction tenantTx,
        Clock clock,
        int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.repo = repo;
        this.tenantTx = tenantTx;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Single scan pass. Tests can drive this directly; the {@code @Scheduled}
     * wrapper in {@code start} invokes the same method.
     *
     * @param tenantIds the set of tenants to scan; in a multi-tenant deployment
     *                  this comes from a tenant-list provider rather than a
     *                  global query (RLS would otherwise hide cross-tenant rows).
     */
    public ScanReport scanOnce(List<String> tenantIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int totalClaimed = 0;
        int totalExpired = 0;

        for (String tenantId : tenantIds) {
            ScanReport perTenant = scanTenant(tenantId, now);
            totalClaimed += perTenant.claimed();
            totalExpired += perTenant.expired();
        }
        return new ScanReport(totalClaimed, totalExpired);
    }

    private ScanReport scanTenant(String tenantId, OffsetDateTime now) {
        return tenantTx.execute(tenantId, "break-glass-scanner", null, () -> {
            List<BreakGlassRequest> claimed = repo.claimExpired(tenantId, batchSize);
            if (claimed.isEmpty()) {
                return new ScanReport(0, 0);
            }
            int expired = 0;
            for (BreakGlassRequest req : claimed) {
                // The repository's claimExpired only returns rows that
                // are APPROVED with valid_until < now, but we still
                // double-check via isActiveAt to defend against a race
                // (e.g., approve() in-flight that pushed valid_until
                // forward between claim and commit).
                if (!req.isActiveAt(now)) {
                    req.expire(now);
                    repo.update(req);
                    expired += 1;
                    LOG.info(
                        "break_glass_expired tenant={} request={} validUntil={}",
                        tenantId, req.id(), req.validUntil()
                    );
                }
            }
            return new ScanReport(claimed.size(), expired);
        });
    }

    public record ScanReport(int claimed, int expired) {}
}
