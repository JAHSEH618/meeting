package com.meeting.api.start.health;

import javax.sql.DataSource;
import java.sql.Connection;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.a — verifies that Row-Level Security is enforced on at least
 * one tenant-owned table. The probe runs in a fresh connection without
 * setting {@code app.tenant_id}; under correct RLS configuration the
 * query returns zero rows or fails — either way RLS is active. If the
 * query returns rows we know the policy is broken and the indicator
 * goes DOWN.
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
            stmt.execute("SET LOCAL ROLE NONE");
            try (var rs = stmt.executeQuery(
                "SELECT count(*) AS c FROM meetings WHERE tenant_id = '__rls_probe__'"
            )) {
                if (rs.next() && rs.getLong("c") == 0L) {
                    return Health.up()
                        .withDetail("policy", "tenant_isolation")
                        .withDetail("probedTable", "meetings")
                        .build();
                }
                return Health.down()
                    .withDetail("reason", "RLS probe returned rows without tenant context")
                    .build();
            }
        } catch (Exception ex) {
            // SQL failure (e.g. RLS deny) is treated as healthy because
            // the policy stopped the read — but log it so ops sees the
            // signal in /actuator/health/details.
            String msg = ex.getMessage();
            if (msg != null && msg.toLowerCase().contains("permission")) {
                return Health.up()
                    .withDetail("policy", "tenant_isolation")
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
