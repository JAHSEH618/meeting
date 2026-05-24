package com.meeting.api.start.config;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;

/**
 * Spring wiring for {@link TenantBootstrap}. Runs once at startup
 * (after Flyway) via {@link ApplicationRunner}. Two beans split by
 * profile so the seed-vs-validate choice can't leak across:
 *
 * <ul>
 *   <li>{@link DevSeed} for {@code !prod}: idempotently inserts every
 *       configured tenant — fixes the out-of-the-box "FK on
 *       tenants(id) fails on first meeting write" trap.</li>
 *   <li>{@link ProdValidate} for {@code prod}: fail-fast if any
 *       configured tenant id is missing from the table.</li>
 * </ul>
 *
 * <p>Both read {@code meeting.tenants.active} via the shared
 * {@link ActiveTenantList} parser so a stray {@code ","} can't sneak
 * through here either.
 */
@Configuration
public class TenantBootstrapConfig {

    @Configuration
    @Profile("!prod")
    public static class DevSeed implements ApplicationRunner {
        private final TenantBootstrap bootstrap;
        private final List<String> tenantIds;

        public DevSeed(
            JdbcTemplate jdbc,
            @Value("${meeting.tenants.active:tenant_default}") String tenantIdsCsv
        ) {
            this.bootstrap = new TenantBootstrap(new JdbcTenantRegistry(jdbc));
            this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
        }

        @Override
        public void run(ApplicationArguments args) {
            bootstrap.run(tenantIds, TenantBootstrap.Mode.SEED_MISSING);
        }
    }

    @Configuration
    @Profile("prod")
    public static class ProdValidate implements ApplicationRunner {
        private final TenantBootstrap bootstrap;
        private final List<String> tenantIds;

        public ProdValidate(
            JdbcTemplate jdbc,
            @Value("${meeting.tenants.active:}") String tenantIdsCsv
        ) {
            this.bootstrap = new TenantBootstrap(new JdbcTenantRegistry(jdbc));
            this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
        }

        @Override
        public void run(ApplicationArguments args) {
            bootstrap.run(tenantIds, TenantBootstrap.Mode.VALIDATE_ONLY);
        }
    }

    static final class JdbcTenantRegistry implements TenantRegistry {
        private final JdbcTemplate jdbc;

        JdbcTenantRegistry(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Set<String> findExisting(Collection<String> tenantIds) {
            if (tenantIds == null || tenantIds.isEmpty()) {
                return Set.of();
            }
            String placeholders = String.join(
                ",", Collections.nCopies(tenantIds.size(), "?")
            );
            List<String> found = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id IN (" + placeholders + ")",
                String.class,
                tenantIds.toArray()
            );
            return new LinkedHashSet<>(found);
        }

        @Override
        public void seed(String tenantId, String displayName) {
            jdbc.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId,
                displayName
            );
        }
    }
}
