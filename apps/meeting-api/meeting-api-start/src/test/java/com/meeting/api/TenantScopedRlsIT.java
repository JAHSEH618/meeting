package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.meeting.MeetingApplicationService;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.infrastructure.persistence.meeting.JdbcMeetingRepository;
import com.meeting.api.infrastructure.tenant.TenantSessionContext;
import com.meeting.api.infrastructure.tenant.TenantTransactionTemplate;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-PostgreSQL guard for the rule: tenant-owned reads MUST run under
 * a {@link TenantScopedTransaction}, otherwise {@code FORCE ROW LEVEL
 * SECURITY} returns empty result sets and writes fail.
 *
 * <p>Verifies the failure mode (RESET → empty), the success mode
 * (set tenant → full result), and the cross-tenant isolation invariant
 * (tenant A cannot see tenant B's rows even when querying via the same
 * service instance).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScopedRlsIT {

    private PostgreSQLContainer<?> postgres;
    private DataSource ds;
    private JdbcTemplate jdbc;
    private MeetingApplicationService service;
    private TenantTransactionTemplate tenantTx;

    @BeforeAll
    void startAndMigrate() throws Exception {
        TestcontainersDockerPreflight.assumeDockerAvailable();

        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("meeting_rls_test")
            .withUsername("meeting")
            .withPassword("meeting_rls_pass");
        postgres.start();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        // Use a non-superuser role so FORCE RLS actually applies. The
        // Testcontainers default user is superuser → it bypasses RLS.
        try (var c = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP ROLE IF EXISTS meeting_app");
            s.execute("CREATE ROLE meeting_app WITH LOGIN PASSWORD 'meeting_app_pass'");
            s.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO meeting_app");
            s.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO meeting_app");
            s.execute("GRANT USAGE ON SCHEMA public TO meeting_app");
            // Seed two tenants for cross-tenant isolation tests.
            s.execute("INSERT INTO tenants (id, name) VALUES "
                + "('tenant_rls_a', 'Tenant A'), "
                + "('tenant_rls_b', 'Tenant B') ON CONFLICT DO NOTHING");
            // meetings.created_by has an FK to users(id); without these
            // rows MeetingApplicationService.create blows up the txn
            // before reset() can clear the GUC. Run as superuser so RLS
            // doesn't block the seed; live tests below run as meeting_app.
            s.execute("INSERT INTO users (id, tenant_id, email, display_name) VALUES "
                + "('user_a', 'tenant_rls_a', 'a@example.com', 'User A'), "
                + "('user_b', 'tenant_rls_b', 'b@example.com', 'User B') "
                + "ON CONFLICT DO NOTHING");
        }

        ds = newDataSource("meeting_app", "meeting_app_pass");
        jdbc = new JdbcTemplate(ds);
        TenantSessionContext sessionContext = new TenantSessionContext(jdbc);
        var txManager = new DataSourceTransactionManager(ds);
        tenantTx = new TenantTransactionTemplate(new TransactionTemplate(txManager), sessionContext);

        service = new MeetingApplicationService(
            new JdbcMeetingRepository(jdbc),
            new NoopMessagePublisher(),
            tenantTx,
            (tid, scope, scopeId) -> false,
            new NoopAuditLogger()
        );
    }

    @AfterAll
    void cleanup() throws Exception {
        if (postgres != null) postgres.stop();
    }

    @Test
    void wrappedReadsSeeRowsCreatedInSameTenant() {
        MeetingDTO created = service.create(new CreateMeetingCommand(
            "tenant_rls_a", "RLS Tenant A meeting",
            null, SecurityLevel.INTERNAL, "zh", List.of(), "user_a"
        ));

        Optional<MeetingDTO> readBack = service.get("tenant_rls_a", created.meetingId());
        assertThat(readBack).isPresent();
        assertThat(readBack.get().meetingId()).isEqualTo(created.meetingId());
        assertThat(service.list("tenant_rls_a")).extracting(MeetingDTO::meetingId).contains(created.meetingId());
    }

    @Test
    void rawReadWithoutTenantContextReturnsEmpty() {
        service.create(new CreateMeetingCommand(
            "tenant_rls_a", "second mtg for A",
            null, SecurityLevel.INTERNAL, "zh", List.of(), "user_a"
        ));

        // Reset any leftover GUC and run a bare SELECT — RLS must hide everything.
        jdbc.execute("RESET app.tenant_id");
        Integer visibleRows = jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM meetings WHERE tenant_id = 'tenant_rls_a'", Integer.class);
        assertThat(visibleRows).isZero();
    }

    @Test
    void crossTenantReadsAreIsolated() {
        MeetingDTO ownedByA = service.create(new CreateMeetingCommand(
            "tenant_rls_a", "private to A",
            null, SecurityLevel.INTERNAL, "zh", List.of(), "user_a"
        ));

        // Same service instance, different tenant context → no leak.
        Optional<MeetingDTO> bSeesA = service.get("tenant_rls_b", ownedByA.meetingId());
        assertThat(bSeesA).isEmpty();

        List<MeetingDTO> listForB = service.list("tenant_rls_b");
        assertThat(listForB).extracting(MeetingDTO::meetingId).doesNotContain(ownedByA.meetingId());
    }

    private DataSource newDataSource(String user, String password) {
        PGSimpleDataSource s = new PGSimpleDataSource();
        s.setUrl(postgres.getJdbcUrl());
        s.setUser(user);
        s.setPassword(password);
        return s;
    }

    private static final class NoopMessagePublisher implements MessagePublisher {
        @Override public void publish(com.meeting.api.domain.common.DomainEvent event) {}
    }

    private static final class NoopAuditLogger implements AuditEventLogger {
        @Override public void log(AuditEntry entry) {}
    }
}
