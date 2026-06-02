package com.meeting.api;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.infrastructure.persistence.meeting.JdbcMeetingRepository;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcMeetingRepositoryIT {
    private static final String TENANT = "tenant_meeting_repo_it";
    private static final String USER = "user_meeting_repo_it";
    private static final String PERSON_1 = "person_meeting_repo_it_1";
    private static final String PERSON_2 = "person_meeting_repo_it_2";
    private static final String APP_USER = "meeting_repo_app";
    private static final String APP_PASSWORD = "meeting_repo_app_pass";

    private PostgreSQLContainer<?> postgres;
    private SingleConnectionDataSource appDs;
    private JdbcTemplate jdbc;
    private JdbcMeetingRepository repository;

    @BeforeAll
    void startAndMigrate() throws Exception {
        TestcontainersDockerPreflight.assumeDockerAvailable();

        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("meeting_repo_test")
            .withUsername("meeting")
            .withPassword("meeting_repo_test");
        postgres.start();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (var conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ROLE IF EXISTS " + APP_USER);
            stmt.execute("CREATE ROLE " + APP_USER + " WITH LOGIN PASSWORD '" + APP_PASSWORD + "'");
            stmt.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO " + APP_USER);
            stmt.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO " + APP_USER);
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'Meeting Repo IT') ON CONFLICT DO NOTHING");
            stmt.execute("INSERT INTO users (id, tenant_id, email, display_name) VALUES "
                + "('" + USER + "', '" + TENANT + "', 'repo@example.com', 'Repo User') ON CONFLICT DO NOTHING");
            stmt.execute("INSERT INTO persons (id, tenant_id, display_name, email) VALUES "
                + "('" + PERSON_1 + "', '" + TENANT + "', '李四', 'li@example.com'), "
                + "('" + PERSON_2 + "', '" + TENANT + "', '王五', 'wang@example.com') "
                + "ON CONFLICT DO NOTHING");
        }

        appDs = new SingleConnectionDataSource(
            postgres.getJdbcUrl(), APP_USER, APP_PASSWORD, true
        );
        appDs.setAutoCommit(true);
        jdbc = new JdbcTemplate(appDs);
        repository = new JdbcMeetingRepository(jdbc);
    }

    @AfterAll
    void stop() {
        if (appDs != null) appDs.destroy();
        if (postgres != null) postgres.stop();
    }

    @Test
    void saveThenFindByIdReadsParticipantsBackFromMeetingParticipants() {
        useTenant();
        jdbc.update("DELETE FROM meeting_participants WHERE tenant_id = ? AND meeting_id = ?", TENANT, "m_repo_it");
        jdbc.update("DELETE FROM meetings WHERE tenant_id = ? AND id = ?", TENANT, "m_repo_it");

        repository.save(new Meeting.Builder()
            .id("m_repo_it")
            .tenantId(TENANT)
            .title("Repo Meeting")
            .securityLevel(SecurityLevel.INTERNAL)
            .status(MeetingStatus.CREATED)
            .language("zh")
            .transcriptVersion(0)
            .minutesVersion(0)
            .scheduledStartAt(OffsetDateTime.parse("2026-06-03T09:30:00Z"))
            .createdAt(OffsetDateTime.parse("2026-06-02T10:00:00Z"))
            .createdBy(USER)
            .participants(List.of(
                new Meeting.Participant(PERSON_1, "李四", "PARTICIPANT"),
                new Meeting.Participant(PERSON_2, "王五", "OBSERVER")
            ))
            .build());

        Meeting reloaded = repository.findById(TENANT, "m_repo_it").orElseThrow();

        assertThat(reloaded.scheduledStartAt()).isEqualTo(OffsetDateTime.parse("2026-06-03T09:30:00Z"));
        assertThat(reloaded.participants())
            .extracting(
                Meeting.Participant::personId,
                Meeting.Participant::displayName,
                Meeting.Participant::role
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(PERSON_1, "李四", "PARTICIPANT"),
                org.assertj.core.groups.Tuple.tuple(PERSON_2, "王五", "OBSERVER")
            );
    }

    private void useTenant() {
        jdbc.execute("SET app.tenant_id = '" + TENANT + "'");
    }
}
