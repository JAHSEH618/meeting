package com.meeting.api;

import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.infrastructure.persistence.task.JdbcCallbackEventRepository;
import java.sql.Statement;
import java.time.OffsetDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCallbackEventRepositoryIT {
    private static final String TENANT = "tenant_callback_it";
    private static final String MEETING = "meeting_callback_it";
    private static final String TASK = "task_callback_it";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-03T03:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private JdbcCallbackEventRepository repo;

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

        ds = new SingleConnectionDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        jdbc = new JdbcTemplate(ds);
        repo = new JdbcCallbackEventRepository(jdbc);
    }

    @AfterAll
    void stop() {
        if (ds != null) ds.destroy();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        try (Statement stmt = ds.getConnection().createStatement()) {
            stmt.execute("SET app.tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM callback_events WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM processing_task_steps WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM processing_tasks WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM meetings WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'Callback IT') ON CONFLICT DO NOTHING");
            stmt.execute(
                "INSERT INTO meetings (id, tenant_id, title, security_level, status, language, transcript_version, minutes_version) "
                    + "VALUES ('" + MEETING + "', '" + TENANT + "', 'Callback', 'INTERNAL', 'PROCESSING', 'zh', 0, 0)"
            );
            stmt.execute(
                "INSERT INTO processing_tasks (id, tenant_id, meeting_id, task_type, phase, status, attempt_count, created_at, updated_at) "
                    + "VALUES ('" + TASK + "', '" + TENANT + "', '" + MEETING + "', 'MEETING_FULL_PIPELINE', "
                    + "'WORKER_DAG_RUNNING', 'RUNNING', 1, now(), now())"
            );
            stmt.execute(
                "INSERT INTO processing_tasks (id, tenant_id, meeting_id, task_type, phase, status, attempt_count, created_at, updated_at) "
                    + "VALUES ('task_callback_other', '" + TENANT + "', '" + MEETING + "', 'MEETING_FULL_PIPELINE', "
                    + "'WORKER_DAG_RUNNING', 'RUNNING', 1, now(), now())"
            );
        }
    }

    @Test
    void recordOnceClassifiesFirstReplayAndBodyHashConflictAtomically() {
        var first = record("idem_01", "body_a", "trace_a");
        var replay = record("idem_01", "body_a", "trace_replay");
        var conflict = record("idem_01", "body_b", "trace_conflict");

        assertThat(repo.recordOnce(first).status()).isEqualTo(CallbackEventRepository.RecordStatus.RECORDED);
        assertThat(repo.recordOnce(replay).status()).isEqualTo(CallbackEventRepository.RecordStatus.REPLAYED);
        assertThat(repo.recordOnce(conflict).status()).isEqualTo(CallbackEventRepository.RecordStatus.BODY_HASH_CONFLICT);

        var stored = repo.findByIdempotencyKey(TENANT, "idem_01").orElseThrow();
        assertThat(stored.bodySha256()).isEqualTo("body_a");
        assertThat(stored.traceId()).isEqualTo("trace_a");
    }

    @Test
    void recordOnceTreatsSameBodyWithDifferentTaskAttemptOrLeaseAsConflict() {
        var first = record("idem_meta", "body_a", "trace_a");
        var differentTask = record("idem_meta", "body_a", "trace_task", "task_callback_other", 1, "worker_01:" + TASK + ":1");
        var differentAttempt = record("idem_meta", "body_a", "trace_attempt", TASK, 2, "worker_01:" + TASK + ":1");
        var differentLease = record("idem_meta", "body_a", "trace_lease", TASK, 1, "worker_01:" + TASK + ":2");

        assertThat(repo.recordOnce(first).status()).isEqualTo(CallbackEventRepository.RecordStatus.RECORDED);
        assertThat(repo.recordOnce(differentTask).status()).isEqualTo(CallbackEventRepository.RecordStatus.BODY_HASH_CONFLICT);
        assertThat(repo.recordOnce(differentAttempt).status()).isEqualTo(CallbackEventRepository.RecordStatus.BODY_HASH_CONFLICT);
        assertThat(repo.recordOnce(differentLease).status()).isEqualTo(CallbackEventRepository.RecordStatus.BODY_HASH_CONFLICT);

        var stored = repo.findByIdempotencyKey(TENANT, "idem_meta").orElseThrow();
        assertThat(stored.taskId()).isEqualTo(TASK);
        assertThat(stored.attemptNo()).isEqualTo(1);
        assertThat(stored.leaseOwner()).isEqualTo("worker_01:" + TASK + ":1");
        assertThat(stored.traceId()).isEqualTo("trace_a");
    }

    private static CallbackEventRepository.CallbackEventRecord record(
        String idempotencyKey,
        String bodySha256,
        String traceId
    ) {
        return record(idempotencyKey, bodySha256, traceId, TASK, 1, "worker_01:" + TASK + ":1");
    }

    private static CallbackEventRepository.CallbackEventRecord record(
        String idempotencyKey,
        String bodySha256,
        String traceId,
        String taskId,
        int attemptNo,
        String leaseOwner
    ) {
        return new CallbackEventRepository.CallbackEventRecord(
            TENANT,
            taskId,
            "worker_01",
            idempotencyKey,
            bodySha256,
            attemptNo,
            leaseOwner,
            "",
            200,
            null,
            traceId,
            NOW
        );
    }
}
