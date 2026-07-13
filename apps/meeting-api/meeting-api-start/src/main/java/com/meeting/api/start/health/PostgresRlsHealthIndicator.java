package com.meeting.api.start.health;

import javax.sql.DataSource;
import java.sql.Connection;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.a — verifies that Row-Level Security is actually enforced for
 * this connection's role. The probe clears {@code app.tenant_id} and
 * counts rows in {@code tenants}, a FORCE-RLS table whose
 * {@code tenant_self} policy ({@code id = current_tenant_id()}) hides
 * every row when no tenant context is set. {@code TenantBootstrap}
 * guarantees the table is non-empty in any working deployment (prod
 * refuses to start when a configured tenant is missing; dev/test seeds
 * it), so:
 *
 * <ul>
 *   <li>count == 0 → RLS filtered the rows → UP.</li>
 *   <li>count &gt; 0 → the role sees rows without tenant context — it
 *       has {@code BYPASSRLS} (e.g. the bootstrap superuser) or the
 *       policy is broken → DOWN with {@code rls=bypassed}.</li>
 * </ul>
 *
 * <p>A probe that filters on a nonexistent tenant id (the previous
 * implementation) returns zero rows whether RLS is enforced or
 * bypassed, and therefore cannot catch the misconfiguration this
 * indicator exists for.</p>
 *
 * <p>A "permission denied" SQL failure still counts as UP — the policy
 * (or a REVOKE) stopped the read. Any other failure (connectivity,
 * missing table) stays DOWN.</p>
 */
@Component("postgresRls")
public class PostgresRlsHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public PostgresRlsHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("RESET app.tenant_id");
            try (var rs = stmt.executeQuery("SELECT count(*) AS c FROM tenants")) {
                long visible = rs.next() ? rs.getLong("c") : 0L;
                if (visible == 0L) {
                    return Health.up()
                        .withDetail("policy", "tenant_self")
                        .withDetail("probedTable", "tenants")
                        .build();
                }
                return Health.down()
                    .withDetail("rls", "bypassed")
                    .withDetail("probedTable", "tenants")
                    .withDetail("visibleRows", visible)
                    .withDetail(
                        "reason",
                        "tenants rows are visible without tenant context — the application"
                            + " role bypasses RLS (BYPASSRLS/superuser) or the policy is broken"
                    )
                    .build();
            }
        } catch (Exception ex) {
            // SQL failure (e.g. RLS deny) is treated as healthy because
            // the policy stopped the read — but log it so ops sees the
            // signal in /actuator/health/details.
            String msg = ex.getMessage();
            if (msg != null && msg.toLowerCase().contains("permission")) {
                return Health.up()
                    .withDetail("policy", "tenant_self")
                    .withDetail("note", "probe denied by RLS as expected")
                    .build();
            }
            return Health.down()
                .withDetail("error", ex.getClass().getSimpleName())
                .withDetail("message", msg)
                .build();
        }
    }
}
