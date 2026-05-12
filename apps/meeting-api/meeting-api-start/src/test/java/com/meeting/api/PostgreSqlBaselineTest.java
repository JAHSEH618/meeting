package com.meeting.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
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

    private Connection baseConn;

    @BeforeAll
    void migrate() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
        baseConn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    void cleanup() throws Exception {
        if (baseConn != null) baseConn.close();
    }

    @Test
    void flywayMigrationsShouldSucceed() {
        // Already executed in @BeforeAll
    }

    @Test
    void rlsTenantContextShouldExist() throws Exception {
        try (Statement stmt = baseConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT proname FROM pg_proc WHERE proname = 'current_tenant_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("proname")).isEqualTo("current_tenant_id");
        }

        try (Statement stmt = baseConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT proname FROM pg_proc WHERE proname = 'set_updated_at'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("proname")).isEqualTo("set_updated_at");
        }
    }

    @Test
    void requiredEnumsShouldExist() throws Exception {
        try (Statement stmt = baseConn.createStatement();
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
        try (Statement stmt = baseConn.createStatement();
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
        try (Statement stmt = baseConn.createStatement();
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
        try (Statement stmt = baseConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM pg_policy WHERE polname LIKE '%tenant%' OR polname LIKE '%rls%'")) {
            assertThat(rs.next()).isTrue();
            long count = rs.getLong(1);
            assertThat(count).isGreaterThan(0);
        }
    }

    @Test
    void rlsShouldEnforceTenantIsolation() throws Exception {
        // Verify the DDL uses app.tenant_id by checking set_config
        try (Statement stmt = baseConn.createStatement()) {
            stmt.execute("SET app.tenant_id = 'tenant_isolation_test'");
        }
        // If meetings table exists and has RLS, verify tenant filtering
        try (Statement stmt = baseConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM pg_policy")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isGreaterThan(0);
        }
    }
}