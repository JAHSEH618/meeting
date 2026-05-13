package com.meeting.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgreSqlBaselineTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg15")
    )
        .withDatabaseName("meeting_test")
        .withUsername("meeting")
        .withPassword("meeting_test");

    private Connection conn;

    @BeforeAll
    void migrate() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
        conn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    void cleanup() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void flywayMigrationsShouldSucceed() {
        // Already executed in @BeforeAll
    }

    @Test
    void rlsTenantContextShouldExist() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT proname FROM pg_proc WHERE proname = 'current_tenant_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("proname")).isEqualTo("current_tenant_id");
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT proname FROM pg_proc WHERE proname = 'set_updated_at'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("proname")).isEqualTo("set_updated_at");
        }
    }

    @Test
    void requiredEnumsShouldExist() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT typname FROM pg_type WHERE typname IN ('security_level', 'task_status', 'task_phase', 'step_status')")) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            assertThat(count).isEqualTo(4);
        }
    }

    @Test
    void stepStatusShouldNotContainPartialSucceeded() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT enumlabel FROM pg_enum WHERE enumtypid = 'step_status'::regtype")) {
            while (rs.next()) {
                assertThat(rs.getString("enumlabel"))
                    .isNotEqualTo("PARTIAL_SUCCEEDED");
            }
        }
    }

    @Test
    void taskStatusShouldContainPartialSucceeded() throws Exception {
        boolean found = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT enumlabel FROM pg_enum WHERE enumtypid = 'task_status'::regtype")) {
            while (rs.next()) {
                if ("PARTIAL_SUCCEEDED".equals(rs.getString("enumlabel"))) {
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void rlsPoliciesShouldExist() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM pg_policy WHERE polname LIKE '%tenant%' OR polname LIKE '%rls%'")) {
            assertThat(rs.next()).isTrue();
            long count = rs.getLong(1);
            assertThat(count).isGreaterThan(0);
        }
    }

    @Test
    void rlsShouldEnforceTenantIsolation() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Verify RLS is enabled on tenant-owned tables
            try (ResultSet rs = stmt.executeQuery(
                "SELECT relname, relrowsecurity FROM pg_class c " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE n.nspname = 'public' AND relrowsecurity = true")) {
                int rlsTableCount = 0;
                while (rs.next()) {
                    rlsTableCount++;
                }
                assertThat(rlsTableCount).isGreaterThan(0);
            }

            // Insert tenant rows — tenants also has FORCE RLS, so we must set
            // app.tenant_id to match the tenant being inserted.
            stmt.execute("SET app.tenant_id = 'tenant_isolation_a'");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('tenant_isolation_a', 'Tenant Isolation A') ON CONFLICT DO NOTHING");
            stmt.execute("SET app.tenant_id = 'tenant_isolation_b'");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('tenant_isolation_b', 'Tenant Isolation B') ON CONFLICT DO NOTHING");

            // Set tenant context using the DDL's convention: app.tenant_id
            stmt.execute("SET app.tenant_id = 'tenant_isolation_a'");

            // Create a test meeting for tenant A — PK column is "id" not "meeting_id"
            stmt.execute("INSERT INTO meetings (id, tenant_id, title, security_level, status, language, transcript_version, minutes_version) " +
                "VALUES ('mtg_rls_test_a', 'tenant_isolation_a', 'RLS Test A', 'INTERNAL', 'CREATED', 'zh', 0, 0) " +
                "ON CONFLICT DO NOTHING");
        }

        // Switch to tenant B and verify tenant A's data is not visible
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET app.tenant_id = 'tenant_isolation_b'");

            try (ResultSet rs = stmt.executeQuery(
                "SELECT id FROM meetings WHERE tenant_id = 'tenant_isolation_a'")) {
                assertThat(rs.next()).isFalse();
            }
        }

        // Clean up — must match tenant_id to each tenant row because of FORCE RLS
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET app.tenant_id = 'tenant_isolation_a'");
            stmt.execute("DELETE FROM meetings WHERE id = 'mtg_rls_test_a'");
            stmt.execute("DELETE FROM tenants WHERE id = 'tenant_isolation_a'");
            stmt.execute("SET app.tenant_id = 'tenant_isolation_b'");
            stmt.execute("DELETE FROM tenants WHERE id = 'tenant_isolation_b'");
        }
    }
}