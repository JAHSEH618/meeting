package com.meeting.api;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.infrastructure.persistence.task.JdbcProcessingTaskRepository;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcProcessingTaskRepositoryIT {
    private static final String TENANT = "tenant_task_order_it";
    private static final String MEETING = "meeting_task_order_it";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-02T10:00:00Z");
    private static final List<ProcessingStep> FULL_TASK_STEPS = List.of(
        ProcessingStep.AUDIO_UPLOAD,
        ProcessingStep.AUDIO_PREPROCESS,
        ProcessingStep.ASR,
        ProcessingStep.ALIGNMENT,
        ProcessingStep.DIARIZATION,
        ProcessingStep.SPEAKER_EMBEDDING,
        ProcessingStep.SPEAKER_MATCHING,
        ProcessingStep.TRANSCRIPT_MERGE,
        ProcessingStep.RAG_INDEXING,
        ProcessingStep.SUMMARY,
        ProcessingStep.EXTRACTION
    );

    private PostgreSQLContainer<?> postgres;
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private JdbcProcessingTaskRepository repo;
    private TransactionTemplate tx;

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
        repo = new JdbcProcessingTaskRepository(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
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
            stmt.execute("DELETE FROM processing_task_steps WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM processing_tasks WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM meetings WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'Task Order IT') ON CONFLICT DO NOTHING");
            stmt.execute(
                "INSERT INTO meetings (id, tenant_id, title, status, language, transcript_version, minutes_version) "
                    + "VALUES ('" + MEETING + "', '" + TENANT + "', 'Task order', 'PROCESSING', 'zh', 0, 0)"
            );
        }
    }

    @Test
    void saveAndReloadPreservesJavaDeclaredTaskStepOrderInsideOneTransaction() {
        ProcessingTask task = ProcessingTask.create(
            "task_order_it",
            TENANT,
            MEETING,
            "MEETING_FULL_PIPELINE",
            FULL_TASK_STEPS,
            NOW
        );

        tx.executeWithoutResult(status -> {
            jdbc.execute("SET app.tenant_id = '" + TENANT + "'");
            repo.save(task);
        });

        ProcessingTask reloaded = repo.findById(TENANT, "task_order_it").orElseThrow();

        assertThat(reloaded.steps())
            .extracting("stepName")
            .containsExactlyElementsOf(FULL_TASK_STEPS);
    }
}
