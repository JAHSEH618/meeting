package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.extraction.ExtractionApplicationService;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.rag.ChunkingApplicationService;
import com.meeting.api.app.rag.MinutesGeneratedRagIndexer;
import com.meeting.api.app.task.JavaLlmPhaseOrchestrator;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.task.ResumeJavaPhaseCommand;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.document.DocumentChunkRepository;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.minutes.MinutesGeneratedEvent;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import com.meeting.api.domain.transcript.TranscriptRepository;
import com.meeting.api.infrastructure.persistence.meeting.JdbcMeetingRepository;
import com.meeting.api.infrastructure.persistence.minutes.JdbcMinutesRepository;
import com.meeting.api.infrastructure.persistence.rag.JdbcKnowledgeChunkRepository;
import com.meeting.api.infrastructure.persistence.task.JdbcProcessingTaskRepository;
import com.meeting.api.infrastructure.persistence.transcript.JdbcTranscriptRepository;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workstation finalize integration flow — todo-final.md B5.6.
 *
 * <p>Exercises the persisted boundary that unit tests cannot cover alone:
 * a held {@code MEETING_FULL_PIPELINE} task reaches {@code WORKER_DAG_DONE},
 * the workstation calls {@code resume-java-phase}, Java generates minutes,
 * the MinutesGenerated listener rebuilds RAG chunks, and the task closes
 * terminal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeetingFinalizeFlowIT {
    private static final String TENANT = "tenant_finalize_it";
    private static final String MEETING = "mtg_finalize_it";
    private static final String TASK = "task_finalize_it";
    private static final String LEASE = "worker_finalize:" + TASK + ":1";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-20T10:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbc;
    private JdbcProcessingTaskRepository taskRepository;
    private JdbcMinutesRepository minutesRepository;
    private JdbcKnowledgeChunkRepository chunkRepository;
    private ProcessingTaskApplicationService taskService;

    @BeforeAll
    void startAndMigrate() {
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
            .placeholderReplacement(false)  // seed SQL uses PostgreSQL $tag$ dollar-quoting; ${ must stay literal
            .load()
            .migrate();

        DataSource ds = newDataSource();
        jdbc = new JdbcTemplate(ds);
        taskRepository = new JdbcProcessingTaskRepository(jdbc);
        minutesRepository = new JdbcMinutesRepository(jdbc, new ObjectMapper());
        chunkRepository = new JdbcKnowledgeChunkRepository(jdbc);
        taskService = buildTaskService();
    }

    @AfterAll
    void stop() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        try (var conn = newDataSource().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET app.tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM knowledge_chunks WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM meeting_risks WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM meeting_decisions WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM meeting_action_items WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM meeting_minutes WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM transcript_segments WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM processing_task_steps WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM processing_tasks WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM meetings WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'Finalize IT') ON CONFLICT DO NOTHING");
            stmt.execute(
                "INSERT INTO meetings (id, tenant_id, title, status, language, transcript_version, minutes_version) "
                    + "VALUES ('" + MEETING + "', '" + TENANT + "', 'Finalize flow', 'PROCESSING', 'zh', 1, 0)"
            );
        }
        seedTranscript();
        seedHeldWorkerDagDoneTask();
    }

    @Test
    void resumeJavaPhaseGeneratesMinutesIndexesRagAndCompletesTask() {
        var dto = taskService.resumeJavaPhase(new ResumeJavaPhaseCommand(
            TENANT, TASK, "user_finalize", "idem_finalize", "req_finalize", "trace_finalize"
        ));

        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.SUCCEEDED);
        assertThat(dto.steps())
            .filteredOn(step -> step.stepName() == ProcessingStep.SUMMARY || step.stepName() == ProcessingStep.EXTRACTION)
            .allSatisfy(step -> assertThat(step.status().name()).isEqualTo("SUCCEEDED"));

        var minutes = minutesRepository.findCurrent(TENANT, MEETING).orElseThrow();
        assertThat(minutes.title()).isEqualTo("Finalize Summary");
        assertThat(minutes.minutesVersion()).isEqualTo(1);

        var chunks = chunkRepository.findByMeetingId(TENANT, MEETING);
        assertThat(chunks)
            .anySatisfy(chunk -> {
                assertThat(chunk.sourceType().name()).isEqualTo("MINUTES");
                assertThat(chunk.content()).contains("Finalize decision");
                assertThat(chunk.minutesVersion()).isEqualTo(1);
            });
    }

    private ProcessingTaskApplicationService buildTaskService() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        JdbcMeetingRepository meetingRepository = new JdbcMeetingRepository(jdbc);
        JdbcTranscriptRepository transcriptRepository = new JdbcTranscriptRepository(jdbc);
        TaskStepProgressService progress = new TaskStepProgressService(
            taskRepository,
            TenantScopedTransaction.immediate(),
            clock
        );
        ChunkingApplicationService chunking = new ChunkingApplicationService(
            transcriptRepository,
            minutesRepository,
            new EmptyActionItems(),
            new EmptyDecisions(),
            new EmptyRisks(),
            new EmptyDocuments(),
            new EmptyDocumentChunks(),
            meetingRepository,
            chunkRepository,
            event -> {
            },
            clock
        );
        MinutesGeneratedRagIndexer indexer = new MinutesGeneratedRagIndexer(chunking, com.meeting.api.app.common.TenantScopedTransaction.immediate(), null);
        ApplicationEventPublisher minutesEvents = event -> {
            if (event instanceof MinutesGeneratedEvent minutesGenerated) {
                indexer.onMinutesGenerated(minutesGenerated);
            }
        };
        FakeLlmGateway llm = new FakeLlmGateway();
        MinutesApplicationService minutesService = new MinutesApplicationService(
            meetingRepository,
            minutesRepository,
            transcriptRepository,
            llm,
            TenantScopedTransaction.immediate(),
            new ObjectMapper(),
            clock,
            minutesEvents,
            new NoopPublisher()
        );
        ExtractionApplicationService extractionService = new ExtractionApplicationService(
            meetingRepository,
            transcriptRepository,
            new EmptyActionItems(),
            new EmptyDecisions(),
            new EmptyRisks(),
            llm,
            TenantScopedTransaction.immediate(),
            new ObjectMapper(),
            clock
        );
        JavaLlmPhaseOrchestrator orchestrator = new JavaLlmPhaseOrchestrator(
            progress,
            taskRepository,
            minutesService,
            extractionService,
            event -> { },
            TenantScopedTransaction.immediate()
        );
        return new ProcessingTaskApplicationService(
            taskRepository,
            meetingRepository,
            new NoopPublisher(),
            TenantScopedTransaction.immediate(),
            clock,
            null,
            null,
            orchestrator
        );
    }

    private void seedTranscript() {
        JdbcTranscriptRepository transcripts = new JdbcTranscriptRepository(jdbc);
        transcripts.replaceTranscript(TENANT, MEETING, 1, null, List.of(
            new TranscriptRepository.TranscriptSegmentRecord(
                "seg_finalize_1",
                TENANT,
                MEETING,
                0,
                0,
                2000,
                "SPEAKER_00",
                "Alice",
                "Finalize decision should be documented.",
                null,
                "Finalize decision should be documented.",
                BigDecimal.valueOf(0.95),
                BigDecimal.valueOf(0.91),
                BigDecimal.valueOf(0.88),
                "SEGMENT",
                1,
                null
            )
        ));
        transcripts.updateMeetingTranscriptVersion(TENANT, MEETING, 1);
    }

    private void seedHeldWorkerDagDoneTask() {
        ProcessingTask task = ProcessingTask.create(
            TASK,
            TENANT,
            MEETING,
            "MEETING_FULL_PIPELINE",
            List.of(
                ProcessingStep.AUDIO_UPLOAD,
                ProcessingStep.AUDIO_PREPROCESS,
                ProcessingStep.ASR,
                ProcessingStep.TRANSCRIPT_MERGE,
                ProcessingStep.SUMMARY,
                ProcessingStep.EXTRACTION
            ),
            NOW,
            true
        );
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        task.claimLease("worker_finalize", LEASE, NOW.plusMinutes(5), NOW);
        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE),
            List.<WorkerPhaseCompletedEvent.SkippedStep>of(),
            1,
            LEASE,
            NOW.plusSeconds(30)
        );
        taskRepository.save(task);
    }

    private DataSource newDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static final class FakeLlmGateway implements LlmGateway {
        @Override
        public LlmResponse complete(LlmRequest request) {
            if ("MINUTES_SUMMARY".equals(request.taskName())) {
                String json = """
                    {
                      "title": "Finalize Summary",
                      "markdown": "## Summary\\nFinalize decision captured.",
                      "sections": [{
                        "type": "SUMMARY",
                        "title": "Summary",
                        "items": [{
                          "text": "Finalize decision captured.",
                          "evidence": [{"segmentId": "seg_finalize_1"}]
                        }]
                      }]
                    }
                    """;
                return new LlmResponse(json, json, 12, 18, 30L, "fake", "llm_finalize_minutes", null);
            }
            String json = """
                {"actionItems": [], "decisions": [], "risks": []}
                """;
            return new LlmResponse(json, json, 8, 10, 20L, "fake", "llm_finalize_extraction", null);
        }
    }

    private static final class NoopPublisher implements MessagePublisher {
        @Override
        public void publish(DomainEvent event) {
        }
    }

    private static final class EmptyActionItems implements ActionItemRepository {
        @Override public String save(ActionItemRecord record) { return record.id(); }
        @Override public List<ActionItemRecord> findByMeeting(String tenantId, String meetingId) { return List.of(); }
        @Override public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {}
        @Override public void markStaleForMeeting(String tenantId, String meetingId) {}
    }

    private static final class EmptyDecisions implements DecisionRepository {
        @Override public String save(DecisionRecord record) { return record.id(); }
        @Override public List<DecisionRecord> findByMeeting(String tenantId, String meetingId) { return List.of(); }
        @Override public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {}
        @Override public void markStaleForMeeting(String tenantId, String meetingId) {}
    }

    private static final class EmptyRisks implements RiskRepository {
        @Override public String save(RiskRecord record) { return record.id(); }
        @Override public List<RiskRecord> findByMeeting(String tenantId, String meetingId) { return List.of(); }
        @Override public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {}
        @Override public void markStaleForMeeting(String tenantId, String meetingId) {}
    }

    private static final class EmptyDocuments implements DocumentRepository {
        @Override public String save(DocumentRecord record) { return record.id(); }
        @Override public Optional<DocumentRecord> findById(String tenantId, String documentId) { return Optional.empty(); }
        @Override public List<DocumentRecord> listByTenant(String tenantId, boolean includeDeleted) { return List.of(); }
        @Override public void updateExtractionStatus(String tenantId, String documentId, String extractionStatus, String status, OffsetDateTime now) {}
        @Override public void softDelete(String tenantId, String documentId, OffsetDateTime now) {}
    }

    private static final class EmptyDocumentChunks implements DocumentChunkRepository {
        @Override public void replaceChunks(String tenantId, String documentId, List<ChunkRecord> chunks, OffsetDateTime now) {}
        @Override public List<ChunkRecord> findByDocument(String tenantId, String documentId) { return List.of(); }
    }
}
