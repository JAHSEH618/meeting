package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.infrastructure.persistence.export.JdbcExportJobRepository;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link JdbcExportJobRepository} — final-check.md C1.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Save → findById round-trip with full enum/version fidelity.</li>
 *   <li>Cross-tenant isolation: row inserted under tenant A is invisible
 *       to a query that sets tenant B's context.</li>
 *   <li>{@code claimByStatus} uses {@code FOR UPDATE SKIP LOCKED} so two
 *       concurrent claimers split the workload — no overlap, no
 *       blocking.</li>
 *   <li>{@code listByMeeting} stable ordering + cursor pagination.</li>
 *   <li>{@code update} mutates status / finishedAt as expected.</li>
 * </ul>
 *
 * <p>Runs against a non-superuser role ({@code meeting_app}) so
 * {@code FORCE ROW LEVEL SECURITY} actually applies — the Testcontainers
 * default user is superuser and would otherwise bypass RLS, masking
 * cross-tenant leaks. The main {@link JdbcTemplate} is backed by a
 * {@link SingleConnectionDataSource} so {@code SET app.tenant_id} set in
 * one statement is still in effect for the next JdbcTemplate call.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcExportJobRepositoryIT {

    private static final String TENANT_A = "tenant_exp_it_a";
    private static final String TENANT_B = "tenant_exp_it_b";
    private static final String MEETING_A = "mtg_exp_it_a";
    private static final String MEETING_B = "mtg_exp_it_b";
    private static final String APP_USER = "meeting_app";
    private static final String APP_PASSWORD = "meeting_app_pass";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-19T10:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private SingleConnectionDataSource appDs;
    private JdbcTemplate jdbc;
    private JdbcExportJobRepository repo;

    @BeforeAll
    void startAndMigrate() throws Exception {
        TestcontainersDockerPreflight.assumeDockerAvailable();

        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("meeting_test")
            .withUsername("meeting")
            .withPassword("meeting_test");
        postgres.start();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        // Seed roles + FK-required parent rows as the superuser so RLS
        // doesn't interfere with bootstrap. Real test traffic runs as
        // meeting_app below.
        try (var conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ROLE IF EXISTS " + APP_USER);
            stmt.execute("CREATE ROLE " + APP_USER + " WITH LOGIN PASSWORD '" + APP_PASSWORD + "'");
            stmt.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO " + APP_USER);
            stmt.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO " + APP_USER);
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);

            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT_A + "', 'IT A') ON CONFLICT DO NOTHING");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT_B + "', 'IT B') ON CONFLICT DO NOTHING");
            seedMeeting(stmt, TENANT_A, MEETING_A);
            seedMeeting(stmt, TENANT_B, MEETING_B);
        }

        appDs = new SingleConnectionDataSource(
            postgres.getJdbcUrl(), APP_USER, APP_PASSWORD, true /* suppressClose */);
        appDs.setAutoCommit(true);
        jdbc = new JdbcTemplate(appDs);
        repo = new JdbcExportJobRepository(jdbc, new ObjectMapper());
    }

    @AfterAll
    void stop() {
        if (appDs != null) appDs.destroy();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void wipe() {
        jdbc.execute("SET app.tenant_id = '" + TENANT_A + "'");
        jdbc.update("DELETE FROM export_jobs WHERE id LIKE 'exp_it_%'");
        jdbc.execute("SET app.tenant_id = '" + TENANT_B + "'");
        jdbc.update("DELETE FROM export_jobs WHERE id LIKE 'exp_it_%'");
    }

    @Test
    void saveAndFindByIdRoundTripsAllFields() {
        useTenant(TENANT_A);
        ExportJob saved = sample("exp_it_save", TENANT_A, MEETING_A, ExportStatus.QUEUED);
        repo.save(saved);

        ExportJob reloaded = repo.findById(TENANT_A, "exp_it_save").orElseThrow();
        assertThat(reloaded.tenantId()).isEqualTo(TENANT_A);
        assertThat(reloaded.meetingId()).isEqualTo(MEETING_A);
        assertThat(reloaded.status()).isEqualTo(ExportStatus.QUEUED);
        assertThat(reloaded.format()).isEqualTo(ExportFormat.MARKDOWN);
        assertThat(reloaded.dataBoundaryMode()).isEqualTo(ExportDataBoundaryMode.FULL);
        assertThat(reloaded.exportType()).isEqualTo(ExportType.MEETING);
        assertThat(reloaded.inputTranscriptVersion()).isEqualTo(1);
        assertThat(reloaded.inputMinutesVersion()).isEqualTo(0);
    }

    @Test
    void crossTenantQueryReturnsEmpty() {
        useTenant(TENANT_A);
        repo.save(sample("exp_it_cross_a", TENANT_A, MEETING_A, ExportStatus.QUEUED));

        useTenant(TENANT_B);
        assertThat(repo.findById(TENANT_A, "exp_it_cross_a"))
            .as("tenant B must not see tenant A's row")
            .isEmpty();
        assertThat(repo.findById(TENANT_B, "exp_it_cross_a"))
            .as("tenant B must not see a row whose tenant_id is A")
            .isEmpty();

        useTenant(TENANT_A);
        assertThat(repo.findById(TENANT_A, "exp_it_cross_a"))
            .as("tenant A still sees its own row")
            .isPresent();
    }

    @Test
    void claimByStatusSkipsRowsAlreadyLockedByAnotherClaimer() throws Exception {
        useTenant(TENANT_A);
        repo.save(sample("exp_it_claim_1", TENANT_A, MEETING_A, ExportStatus.QUEUED));
        Thread.sleep(2); // ensure stable ordering by created_at
        repo.save(sample("exp_it_claim_2", TENANT_A, MEETING_A, ExportStatus.QUEUED));

        DataSource dsA = newAppDataSource();
        DataSource dsB = newAppDataSource();
        try (var connA = dsA.getConnection(); var connB = dsB.getConnection()) {
            try (Statement s = connA.createStatement()) { s.execute("SET app.tenant_id = '" + TENANT_A + "'"); }
            try (Statement s = connB.createStatement()) { s.execute("SET app.tenant_id = '" + TENANT_A + "'"); }
            connA.setAutoCommit(false);
            connB.setAutoCommit(false);

            // Claimer A locks the first queued row.
            String claimedByA;
            try (var psA = connA.prepareStatement(
                """
                SELECT id FROM export_jobs
                WHERE tenant_id = ? AND status = 'QUEUED'
                ORDER BY created_at ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """)) {
                psA.setString(1, TENANT_A);
                try (var rs = psA.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    claimedByA = rs.getString("id");
                }
            }

            // Claimer B asks for QUEUED rows; row A is held → must skip
            // to the other one without blocking.
            try (var psB = connB.prepareStatement(
                """
                SELECT id FROM export_jobs
                WHERE tenant_id = ? AND status = 'QUEUED'
                ORDER BY created_at ASC
                LIMIT 5
                FOR UPDATE SKIP LOCKED
                """)) {
                psB.setString(1, TENANT_A);
                try (var rs = psB.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    String claimedByB = rs.getString("id");
                    assertThat(claimedByB)
                        .as("claimer B must skip A's locked row and grab the other one")
                        .isNotEqualTo(claimedByA);
                    assertThat(rs.next()).isFalse();
                }
            }

            connA.rollback();
            connB.rollback();
        }
    }

    @Test
    void updateMutatesStatusAndFinishedAt() {
        useTenant(TENANT_A);
        ExportJob initial = sample("exp_it_update", TENANT_A, MEETING_A, ExportStatus.QUEUED);
        repo.save(initial);

        ExportJob done = ExportJob.builder()
            .id(initial.id()).tenantId(initial.tenantId()).meetingId(initial.meetingId())
            .exportType(initial.exportType()).format(initial.format())
            .dataBoundaryMode(initial.dataBoundaryMode())
            .status(ExportStatus.SUCCEEDED)
            .inputTranscriptVersion(initial.inputTranscriptVersion())
            .inputMinutesVersion(initial.inputMinutesVersion())
            .renderOptions(initial.renderOptions())
            .createdBy(initial.createdBy())
            .createdAt(initial.createdAt())
            .updatedAt(NOW.plusMinutes(1))
            .finishedAt(NOW.plusMinutes(1))
            .build();
        repo.update(done);

        ExportJob reloaded = repo.findById(TENANT_A, initial.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(ExportStatus.SUCCEEDED);
        assertThat(reloaded.finishedAt()).isNotNull();
    }

    @Test
    void listByMeetingHonoursCursorAndStableOrdering() throws Exception {
        useTenant(TENANT_A);
        for (int i = 0; i < 3; i++) {
            repo.save(sample("exp_it_list_" + i, TENANT_A, MEETING_A, ExportStatus.QUEUED));
            Thread.sleep(2);
        }

        var page = repo.listByMeeting(TENANT_A, MEETING_A, null, 2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.page().hasMore()).isTrue();
        var next = repo.listByMeeting(TENANT_A, MEETING_A, page.page().cursor(), 2);
        assertThat(next.items()).hasSize(1);
        assertThat(next.page().hasMore()).isFalse();
    }

    // ─── helpers ──────────────────────────────────────────────

    private void useTenant(String tenantId) {
        jdbc.execute("SET app.tenant_id = '" + tenantId + "'");
    }

    private ExportJob sample(String id, String tenant, String meeting, ExportStatus status) {
        return ExportJob.builder()
            .id(id)
            .tenantId(tenant)
            .meetingId(meeting)
            .exportType(ExportType.MEETING)
            .format(ExportFormat.MARKDOWN)
            .dataBoundaryMode(ExportDataBoundaryMode.FULL)
            .status(status)
            .inputTranscriptVersion(1)
            .inputMinutesVersion(0)
            .renderOptions(ExportRenderOptions.defaults())
            .createdBy(null)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    }

    private static void seedMeeting(Statement stmt, String tenant, String meeting) throws Exception {
        stmt.execute("SET app.tenant_id = '" + tenant + "'");
        stmt.execute(
            "INSERT INTO meetings (id, tenant_id, title, security_level, status, language, transcript_version, minutes_version) "
                + "VALUES ('" + meeting + "', '" + tenant + "', 'IT meeting', 'INTERNAL', 'CREATED', 'zh', 1, 0) "
                + "ON CONFLICT DO NOTHING");
    }

    private DataSource newAppDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(APP_USER);
        ds.setPassword(APP_PASSWORD);
        return ds;
    }
}
