package com.meeting.api.start.config;

import java.util.Collection;
import java.util.Set;

/**
 * Port for {@link TenantBootstrap}. Two impls:
 * <ul>
 *   <li>{@code JdbcTenantRegistry} — production wiring against the
 *       {@code tenants} table (queried with a single
 *       {@code SELECT id FROM tenants WHERE id = ANY(?)}).</li>
 *   <li>A test double — see {@code TenantBootstrapTest}.</li>
 * </ul>
 */
public interface TenantRegistry {

    /**
     * Returns the subset of {@code tenantIds} that exist in the
     * {@code tenants} table. Implementations must run the query
     * outside any tenant-RLS scope — this is bootstrap code, not a
     * per-tenant request.
     */
    Set<String> findExisting(Collection<String> tenantIds);

    /**
     * Idempotently inserts a tenant row. Implementations should use
     * {@code ON CONFLICT (id) DO NOTHING} so concurrent boots and
     * re-runs don't conflict.
     */
    void seed(String tenantId, String displayName);
}
