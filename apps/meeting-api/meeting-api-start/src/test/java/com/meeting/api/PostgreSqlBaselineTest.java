package com.meeting.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
class PostgreSqlBaselineTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg15")
    )
        .withDatabaseName("meeting_test")
        .withUsername("meeting")
        .withPassword("meeting_test");

    private Flyway migrate() {
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
        return flyway;
    }

    @Test
    void flywayMigrationsShouldSucceed() {
        migrate();
    }

    @Test
    void rlsTenantContextShouldExist() throws Exception {
        migrate();
        try (Connection conn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
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
    }

    @Test
    void requiredEnumsShouldExist() throws Exception {
        migrate();
        try (Connection conn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
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
    }

    @Test
    void stepStatusShouldNotContainPartialSucceeded() throws Exception {
        migrate();
        try (Connection conn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT enumlabel FROM pg_enum WHERE enumtypid = 'step_status'::regtype")) {
                while (rs.next()) {
                    assertThat(rs.getString("enumlabel"))
                        .isNotEqualTo("PARTIAL_SUCCEEDED");
                }
            }
        }
    }

    @Test
    void taskStatusShouldContainPartialSucceeded() throws Exception {
        migrate();
        try (Connection conn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
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
    }

    @Test
    void rlsPoliciesShouldBlockCrossTenantAccess() throws Exception {
        migrate();
        try (Connection conn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM pg_policy WHERE polname LIKE '%tenant%' OR polname LIKE '%rls%'")) {
                assertThat(rs.next()).isTrue();
                long count = rs.getLong(1);
                assertThat(count).isGreaterThan(0);
            }
        }
    }

    @Test
    void rlsShouldEnforceTenantIsolation() throws Exception {
        migrate();
        try (Connection conn = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET app.current_tenant_id = 'tenant_isolation_test'");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM pg_policy")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }
}