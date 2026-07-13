package com.meeting.api;

import com.meeting.api.app.task.JavaLlmPhaseOrchestrator;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.app.task.WorkerPhaseCompletedListener;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import com.meeting.api.infrastructure.persistence.task.JdbcProcessingTaskRepository;
import com.meeting.api.infrastructure.tenant.TenantSessionContext;
import com.meeting.api.infrastructure.tenant.TenantTransactionTemplate;
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

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that WorkerPhaseCompletedListener and
 * JavaLlmPhaseOrchestrator work correctly under RLS constraints
 * with a non-superuser database role.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerPhaseCompletedListenerRlsIT {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private PostgreSQLContainer<?> postgres;
    private DataSource ds;
    private JdbcTemplate jdbc;
    private ProcessingTaskRepository taskRepository;
    private TenantTransactionTemplate tenantTx;
    private WorkerPhaseCompletedListener listener;

    @BeforeAll
    void startAndMigrate() throws Exception {
        TestcontainersDockerPreflight.assumeDockerAvailable();

        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("meeting_listener_rls_test")
            .withUsername("meeting")
            .withPassword("meeting_pass");
        postgres.start();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .placeholderReplacement(false)  // seed SQL uses PostgreSQL $tag$ dollar-quoting; ${ must stay literal
            .load()
            .migrate();

        // Use a non-superuser role so FORCE RLS actually applies
        try (var c = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP ROLE IF EXISTS meeting_app");
            s.execute("CREATE ROLE meeting_app WITH LOGIN PASSWORD 'meeting_app_pass'");
            s.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO meeting_app");
            s.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO meeting_app");
            s.execute("GRANT USAGE ON SCHEMA public TO meeting_app");
            // Seed tenants
            s.execute("INSERT INTO tenants (id, name) VALUES "
                + "('tenant_a', 'Tenant A'), "
                + "('tenant_b', 'Tenant B') ON CONFLICT DO NOTHING");
            // Seed users
            s.execute("INSERT INTO users (id, tenant_id, email, display_name) VALUES "
                + "('user_a', 'tenant_a', 'a@example.com', 'User A'), "
                + "('user_b', 'tenant_b', 'b@example.com', 'User B') "
                + "ON CONFLICT DO NOTHING");
            // Seed meetings
            s.execute("INSERT INTO meetings (id, tenant_id, title, language, created_by) VALUES "
                + "('meeting_a', 'tenant_a', 'Meeting A', 'zh', 'user_a'), "
                + "('meeting_b', 'tenant_b', 'Meeting B', 'zh', 'user_b') "
                + "ON CONFLICT DO NOTHING");
        }

        ds = newDataSource("meeting_app", "meeting_app_pass");
        jdbc = new JdbcTemplate(ds);
        TenantSessionContext sessionContext = new TenantSessionContext(jdbc);
        var txManager = new DataSourceTransactionManager(ds);
        tenantTx = new TenantTransactionTemplate(new TransactionTemplate(txManager), sessionContext);

        taskRepository = new JdbcProcessingTaskRepository(jdbc);
        TaskStepProgressService progressService = new TaskStepProgressService(
            taskRepository,
            tenantTx,
            CLOCK
        );

        // Create listener without orchestrator/autoConfirm for simplicity
        listener = new WorkerPhaseCompletedListener(
            progressService,
            taskRepository,
            tenantTx
        );
    }

    @AfterAll
    void cleanup() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void listenerCanReadAndUpdateTaskUnderRls() {
        // Create a task in tenant_a
        String taskId = createTask("tenant_a", "meeting_a", "MEETING_FULL_PIPELINE");

        // Move task to WORKER_DAG_DONE
        moveToWorkerDagDone(taskId, "tenant_a");

        // Fire listener event
        WorkerPhaseCompletedEvent event = new WorkerPhaseCompletedEvent(
            "evt_01",
            "tenant_a",
            taskId,
            "MEETING_FULL_PIPELINE",
            1,
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE),
            List.of(),
            null,
            0L,
            NOW
        );

        listener.onWorkerPhaseCompleted(event);

        // Verify task moved to JAVA_LLM_RUNNING
        ProcessingTask task = tenantTx.execute("tenant_a", null, null, () ->
            taskRepository.findById("tenant_a", taskId).orElseThrow()
        );
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
    }

    @Test
    void listenerCannotAccessCrossTenantTasks() {
        // Create task in tenant_a
        String taskIdA = createTask("tenant_a", "meeting_a", "MEETING_FULL_PIPELINE");
        moveToWorkerDagDone(taskIdA, "tenant_a");

        // Try to fire event with tenant_b's context but referencing tenant_a's task
        WorkerPhaseCompletedEvent event = new WorkerPhaseCompletedEvent(
            "evt_02",
            "tenant_b",  // Wrong tenant!
            taskIdA,     // Task belongs to tenant_a
            "MEETING_FULL_PIPELINE",
            1,
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS),
            List.of(),
            null,
            0L,
            NOW
        );

        // Listener should handle gracefully (log warning, but not crash)
        listener.onWorkerPhaseCompleted(event);

        // Task should remain at WORKER_DAG_DONE because listener couldn't read it
        ProcessingTask task = tenantTx.execute("tenant_a", null, null, () ->
            taskRepository.findById("tenant_a", taskIdA).orElseThrow()
        );
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.WORKER_DAG_DONE);
    }

    @Test
    void nonMeetingFullPipelineTaskMovesToTerminal() {
        String taskId = createTask("tenant_a", "meeting_a", "SPEAKER_ENROLLMENT");
        moveToWorkerDagDone(taskId, "tenant_a");

        WorkerPhaseCompletedEvent event = new WorkerPhaseCompletedEvent(
            "evt_03",
            "tenant_a",
            taskId,
            "SPEAKER_ENROLLMENT",
            1,
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING),
            List.of(),
            null,
            0L,
            NOW
        );

        listener.onWorkerPhaseCompleted(event);

        ProcessingTask task = tenantTx.execute("tenant_a", null, null, () ->
            taskRepository.findById("tenant_a", taskId).orElseThrow()
        );
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.SUCCEEDED);
    }

    private String createTask(String tenantId, String meetingId, String taskType) {
        return tenantTx.execute(tenantId, null, null, () -> {
            List<ProcessingStep> steps = "MEETING_FULL_PIPELINE".equals(taskType)
                ? List.of(
                    ProcessingStep.AUDIO_UPLOAD,
                    ProcessingStep.AUDIO_PREPROCESS,
                    ProcessingStep.ASR,
                    ProcessingStep.TRANSCRIPT_MERGE,
                    ProcessingStep.SUMMARY,
                    ProcessingStep.EXTRACTION
                )
                : List.of(
                    ProcessingStep.SPEAKER_EMBEDDING,
                    ProcessingStep.SPEAKER_MATCHING
                );

            String taskId = "task_" + java.util.UUID.randomUUID().toString().substring(0, 8);
            ProcessingTask task = ProcessingTask.create(
                taskId,
                tenantId,
                meetingId,
                taskType,
                steps,
                NOW,
                false
            );
            if ("MEETING_FULL_PIPELINE".equals(taskType)) {
                task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
            }
            taskRepository.save(task);
            return taskId;
        });
    }

    private void moveToWorkerDagDone(String taskId, String tenantId) {
        tenantTx.executeWithoutResult(tenantId, null, null, () -> {
            ProcessingTask task = taskRepository.findById(tenantId, taskId).orElseThrow();
            task.enqueue(NOW);
            task.claimLease("worker_01", "worker_01:" + taskId + ":1", NOW.plusMinutes(5), NOW);
            List<ProcessingStep> workerSteps = "MEETING_FULL_PIPELINE".equals(task.taskType())
                ? List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE)
                : List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING);
            task.completeWorkerPhase(
                ProcessingTaskStatus.SUCCEEDED,
                workerSteps,
                List.of(),
                1,
                "worker_01:" + taskId + ":1",
                NOW.plusMinutes(1)
            );
            taskRepository.save(task);
        });
    }

    private DataSource newDataSource(String user, String password) {
        PGSimpleDataSource s = new PGSimpleDataSource();
        s.setUrl(postgres.getJdbcUrl());
        s.setUser(user);
        s.setPassword(password);
        return s;
    }
}
