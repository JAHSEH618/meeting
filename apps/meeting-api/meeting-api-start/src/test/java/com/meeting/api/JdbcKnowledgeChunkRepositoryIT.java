package com.meeting.api;

import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.domain.rag.ChunkStatus;
import com.meeting.api.domain.rag.KnowledgeChunk;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import com.meeting.api.infrastructure.persistence.rag.JdbcKnowledgeChunkRepository;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcKnowledgeChunkRepositoryIT {

    private static final String TENANT = "tenant_kc_it";
    private static final String MEETING = "mtg_kc_it";
    private static final String DOCUMENT = "doc_kc_it";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T10:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbc;
    private JdbcKnowledgeChunkRepository repo;

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

        DataSource ds = newDataSource();
        jdbc = new JdbcTemplate(ds);
        repo = new JdbcKnowledgeChunkRepository(jdbc);

        // Seed tenant + meeting + document required by FK constraints.
        try (var conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET app.tenant_id = '" + TENANT + "'");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'KC IT Tenant') ON CONFLICT DO NOTHING");
            stmt.execute("INSERT INTO meetings (id, tenant_id, title, security_level, status, language, transcript_version, minutes_version) "
                + "VALUES ('" + MEETING + "', '" + TENANT + "', 'Test', 'INTERNAL', 'CREATED', 'zh', 1, 0) "
                + "ON CONFLICT DO NOTHING");
            stmt.execute("INSERT INTO meeting_files (id, tenant_id, file_type, file_purpose, bucket, object_key, uri, upload_status) "
                + "VALUES ('file_kc_it', '" + TENANT + "', 'DOCUMENT', 'KNOWLEDGE', 'documents', 'kc-it.pdf', 'tos://documents/kc-it.pdf', 'COMPLETED') "
                + "ON CONFLICT DO NOTHING");
            stmt.execute("INSERT INTO documents (id, tenant_id, title, file_id, document_type, status, security_level, "
                + "text_extraction_status, content_hash, created_by, created_at, updated_at) "
                + "VALUES ('" + DOCUMENT + "', '" + TENANT + "', 'Doc', 'file_kc_it', 'PDF', 'UPLOADED', 'CONFIDENTIAL', "
                + "'EXTRACTED', 'sha256:doc', NULL, now(), now()) ON CONFLICT DO NOTHING");
        }
    }

    @BeforeEach
    void wipeChunks() throws Exception {
        try (var conn = newDataSource().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET app.tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM knowledge_chunks WHERE tenant_id = '" + TENANT + "'");
        }
    }

    @AfterAll
    void stop() {
        if (postgres != null) postgres.stop();
    }

    private DataSource newDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    @Test
    void saveAllPersistsTranscriptChunkAndFindByMeetingReadsItBack() throws Exception {
        setTenantContext();

        float[] vec = randomVector(1024, 7);
        KnowledgeChunk chunk = KnowledgeChunk.builder()
            .id("chunk_save_1")
            .tenantId(TENANT)
            .meetingId(MEETING)
            .sourceType(KnowledgeSourceType.PRIMARY_TRANSCRIPT)
            .sourceId("seg_1#0")
            .sourceSegmentId("seg_1")
            .content("第一句会议内容。")
            .contentHash("hash_1")
            .chunkStrategyVersion("default-zh-v1")
            .transcriptVersion(1)
            .securityLevel(SecurityLevel.INTERNAL)
            .embedding(vec)
            .embeddingModelVersion("bge-m3-v1")
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();

        repo.saveAll(List.of(chunk));

        List<KnowledgeChunk> fetched = repo.findByMeetingId(TENANT, MEETING);
        assertThat(fetched).hasSize(1);
        KnowledgeChunk r = fetched.get(0);
        assertThat(r.id()).isEqualTo("chunk_save_1");
        assertThat(r.tenantId()).isEqualTo(TENANT);
        assertThat(r.meetingId()).isEqualTo(MEETING);
        assertThat(r.documentId()).isNull();
        assertThat(r.sourceType()).isEqualTo(KnowledgeSourceType.PRIMARY_TRANSCRIPT);
        assertThat(r.sourceId()).isEqualTo("seg_1#0");
        assertThat(r.sourceSegmentId()).isEqualTo("seg_1");
        assertThat(r.content()).isEqualTo("第一句会议内容。");
        assertThat(r.contentHash()).isEqualTo("hash_1");
        assertThat(r.chunkStrategyVersion()).isEqualTo("default-zh-v1");
        assertThat(r.transcriptVersion()).isEqualTo(1);
        assertThat(r.minutesVersion()).isNull();
        assertThat(r.securityLevel()).isEqualTo(SecurityLevel.INTERNAL);
        assertThat(r.status()).isEqualTo(ChunkStatus.ACTIVE);
        assertThat(r.staleStatus()).isEqualTo(StaleStatus.ACTIVE);
        assertThat(r.embeddingModelVersion()).isEqualTo("bge-m3-v1");
        assertThat(r.embedding()).hasSize(1024);
        assertThat(r.embedding()[0]).isCloseTo(vec[0], org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(r.embedding()[1023]).isCloseTo(vec[1023], org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    void saveAllPersistsDocumentChunkWithoutEmbedding() throws Exception {
        setTenantContext();

        KnowledgeChunk chunk = KnowledgeChunk.builder()
            .id("chunk_doc_1")
            .tenantId(TENANT)
            .documentId(DOCUMENT)
            .sourceType(KnowledgeSourceType.DOCUMENT)
            .sourceId("src_1#0")
            .content("文档第一段。")
            .contentHash("hash_doc_1")
            .chunkStrategyVersion("default-zh-v1")
            .securityLevel(SecurityLevel.CONFIDENTIAL)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();

        repo.saveAll(List.of(chunk));

        List<KnowledgeChunk> fetched = repo.findByDocumentId(TENANT, DOCUMENT);
        assertThat(fetched).hasSize(1);
        KnowledgeChunk r = fetched.get(0);
        assertThat(r.documentId()).isEqualTo(DOCUMENT);
        assertThat(r.meetingId()).isNull();
        assertThat(r.securityLevel()).isEqualTo(SecurityLevel.CONFIDENTIAL);
        assertThat(r.embedding()).isNull();
        assertThat(r.hasEmbedding()).isFalse();
    }

    @Test
    void saveAllUpsertsOnConflictId() throws Exception {
        setTenantContext();

        KnowledgeChunk first = KnowledgeChunk.builder()
            .id("chunk_upsert")
            .tenantId(TENANT).meetingId(MEETING)
            .sourceType(KnowledgeSourceType.PRIMARY_TRANSCRIPT)
            .sourceId("seg_x#0").sourceSegmentId("seg_x")
            .content("旧内容").contentHash("h1")
            .chunkStrategyVersion("default-zh-v1")
            .transcriptVersion(1)
            .securityLevel(SecurityLevel.INTERNAL)
            .createdAt(NOW).updatedAt(NOW)
            .build();

        repo.saveAll(List.of(first));

        KnowledgeChunk second = KnowledgeChunk.builder()
            .id("chunk_upsert")
            .tenantId(TENANT).meetingId(MEETING)
            .sourceType(KnowledgeSourceType.PRIMARY_TRANSCRIPT)
            .sourceId("seg_x#0").sourceSegmentId("seg_x")
            .content("新内容").contentHash("h2")
            .chunkStrategyVersion("default-zh-v1")
            .transcriptVersion(2)
            .securityLevel(SecurityLevel.INTERNAL)
            .createdAt(NOW).updatedAt(NOW.plusMinutes(1))
            .build();

        repo.saveAll(List.of(second));

        List<KnowledgeChunk> fetched = repo.findByMeetingId(TENANT, MEETING);
        assertThat(fetched).hasSize(1);
        assertThat(fetched.get(0).content()).isEqualTo("新内容");
        assertThat(fetched.get(0).transcriptVersion()).isEqualTo(2);
        assertThat(fetched.get(0).contentHash()).isEqualTo("h2");
    }

    @Test
    void saveAllOnEmptyIsNoOp() {
        repo.saveAll(List.of());
        repo.saveAll(null);
        // no exception thrown; nothing inserted
    }

    @Test
    void markStaleForMeetingFlipsAllActiveChunks() throws Exception {
        setTenantContext();

        repo.saveAll(List.of(
            simpleMeetingChunk("chunk_ms_a", "a"),
            simpleMeetingChunk("chunk_ms_b", "b")
        ));

        int touched = repo.markStaleForMeeting(TENANT, MEETING);

        assertThat(touched).isEqualTo(2);
        List<KnowledgeChunk> after = repo.findByMeetingId(TENANT, MEETING);
        assertThat(after).allSatisfy(c -> assertThat(c.staleStatus()).isEqualTo(StaleStatus.STALE));

        // Subsequent call only touches ACTIVE rows — nothing left, so 0
        int second = repo.markStaleForMeeting(TENANT, MEETING);
        assertThat(second).isEqualTo(0);
    }

    @Test
    void markStaleForDocumentOnlyTouchesThatDocument() throws Exception {
        setTenantContext();

        repo.saveAll(List.of(
            simpleDocumentChunk("chunk_msd_a", "a"),
            simpleDocumentChunk("chunk_msd_b", "b"),
            simpleMeetingChunk("chunk_msd_keep", "keep")
        ));

        int touched = repo.markStaleForDocument(TENANT, DOCUMENT);
        assertThat(touched).isEqualTo(2);

        // Meeting-attached chunk untouched.
        var meetingChunks = repo.findByMeetingId(TENANT, MEETING);
        assertThat(meetingChunks)
            .filteredOn(c -> "chunk_msd_keep".equals(c.id()))
            .singleElement()
            .satisfies(c -> assertThat(c.staleStatus()).isEqualTo(StaleStatus.ACTIVE));
    }

    @Test
    void updateStaleStatusFlipsOnlySpecifiedIds() throws Exception {
        setTenantContext();

        repo.saveAll(List.of(
            simpleMeetingChunk("chunk_us_1", "1"),
            simpleMeetingChunk("chunk_us_2", "2"),
            simpleMeetingChunk("chunk_us_3", "3")
        ));

        int touched = repo.updateStaleStatus(TENANT, List.of("chunk_us_1", "chunk_us_3"), StaleStatus.REBUILD_QUEUED);
        assertThat(touched).isEqualTo(2);

        List<KnowledgeChunk> fetched = repo.findByMeetingId(TENANT, MEETING);
        assertThat(fetched).extracting(KnowledgeChunk::id, KnowledgeChunk::staleStatus)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("chunk_us_1", StaleStatus.REBUILD_QUEUED),
                org.assertj.core.groups.Tuple.tuple("chunk_us_2", StaleStatus.ACTIVE),
                org.assertj.core.groups.Tuple.tuple("chunk_us_3", StaleStatus.REBUILD_QUEUED)
            );
    }

    @Test
    void updateStaleStatusOnEmptyIdsIsNoOp() {
        int touched = repo.updateStaleStatus(TENANT, List.of(), StaleStatus.STALE);
        assertThat(touched).isEqualTo(0);
    }

    @Test
    void markEmbeddingsStillUnimplementedSoCallersFailFast() {
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> repo.markEmbedding(TENANT, "chunk_x", new float[] {0.1f}, "bge-m3-v1")
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> repo.markEmbeddings(TENANT, java.util.Map.of())
        );
    }

    @Test
    void searchByVectorReturnsEmptyUntilC15() {
        List<?> r1 = repo.searchByVector(TENANT, new float[] {0.0f}, KnowledgeChunkRepository.RetrievalScope.EMPTY, 5);
        List<?> r2 = repo.searchByKeyword(TENANT, "anything", KnowledgeChunkRepository.RetrievalScope.EMPTY, 5);
        assertThat(r1).isEmpty();
        assertThat(r2).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────

    private void setTenantContext() throws Exception {
        jdbc.execute("SET app.tenant_id = '" + TENANT + "'");
    }

    private static KnowledgeChunk simpleMeetingChunk(String id, String suffix) {
        return KnowledgeChunk.builder()
            .id(id)
            .tenantId(TENANT).meetingId(MEETING)
            .sourceType(KnowledgeSourceType.PRIMARY_TRANSCRIPT)
            .sourceId("seg_" + suffix).sourceSegmentId("seg_" + suffix)
            .content("内容 " + suffix).contentHash("h_" + suffix)
            .chunkStrategyVersion("default-zh-v1")
            .transcriptVersion(1)
            .securityLevel(SecurityLevel.INTERNAL)
            .createdAt(NOW).updatedAt(NOW)
            .build();
    }

    private static KnowledgeChunk simpleDocumentChunk(String id, String suffix) {
        return KnowledgeChunk.builder()
            .id(id)
            .tenantId(TENANT).documentId(DOCUMENT)
            .sourceType(KnowledgeSourceType.DOCUMENT)
            .sourceId("src_" + suffix)
            .content("文档 " + suffix).contentHash("hd_" + suffix)
            .chunkStrategyVersion("default-zh-v1")
            .securityLevel(SecurityLevel.CONFIDENTIAL)
            .createdAt(NOW).updatedAt(NOW)
            .build();
    }

    private static float[] randomVector(int dim, long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = (r.nextFloat() - 0.5f) * 2.0f;
        return v;
    }
}
