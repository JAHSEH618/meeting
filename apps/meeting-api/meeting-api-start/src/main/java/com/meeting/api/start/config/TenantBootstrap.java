package com.meeting.api.start.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boot-time check for {@code meeting.tenants.active}. Without it, the
 * very first write of a default install fails with a foreign-key
 * violation against {@code tenants(id)} because nothing seeds the
 * default tenant; in prod, a typo in {@code MEETING_TENANTS_ACTIVE}
 * silently makes every scheduler do nothing.
 *
 * <p>Two modes — wired separately per Spring profile:
 * <ul>
 *   <li>{@link Mode#SEED_MISSING} (dev/test): idempotently inserts
 *       any configured tenant that doesn't already exist, using a
 *       placeholder display name. Safe to re-run.</li>
 *   <li>{@link Mode#VALIDATE_ONLY} (prod): refuses to start if any
 *       configured tenant id is missing — auto-seeding in prod would
 *       hide an operator misconfiguration that schedulers depend on.</li>
 * </ul>
 */
public final class TenantBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(TenantBootstrap.class);

    public enum Mode { SEED_MISSING, VALIDATE_ONLY }

    private final TenantRegistry registry;

    public TenantBootstrap(TenantRegistry registry) {
        this.registry = registry;
    }

    public void run(List<String> configuredTenantIds, Mode mode) {
        if (configuredTenantIds == null || configuredTenantIds.isEmpty()) {
            return;
        }
        Set<String> existing = registry.findExisting(configuredTenantIds);
        List<String> missing = new ArrayList<>();
        for (String tenantId : configuredTenantIds) {
            if (!existing.contains(tenantId)) {
                missing.add(tenantId);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (mode == Mode.VALIDATE_ONLY) {
            throw new IllegalStateException(
                "meeting.tenants.active references tenant ids that are not present in"
                    + " the tenants table: " + missing
                    + " — refusing to start. Insert these rows or update"
                    + " MEETING_TENANTS_ACTIVE before re-deploying."
            );
        }
        for (String tenantId : missing) {
            registry.seed(tenantId, defaultDisplayName(tenantId));
            LOG.info("tenant_bootstrap_seeded tenantId={} (dev/test default)", tenantId);
        }
    }

    private static String defaultDisplayName(String tenantId) {
        return "tenant_default".equals(tenantId) ? "Default Tenant" : tenantId;
    }
}
