package com.meeting.api.infrastructure.persistence.rag;

import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.domain.rag.ChunkStatus;
import com.meeting.api.domain.rag.KnowledgeChunk;
import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed {@link KnowledgeChunkRepository}.
 *
 * <p>Phase 5 M5A C10 wires read / write of the {@code knowledge_chunks} table.
 * Vector + keyword retrieval lands in M5B C15; the embedding writeback path
 * lands in M5A C13.
 *
 * <p>Vector values are serialised to pgvector's text format
 * ({@code "[1.0,2.0,...]"}) and inserted with a {@code ?::vector} cast. The
 * pg-jdbc driver returns them in the same shape on read, which we parse back
 * to {@code float[]}.
 */
@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveAll(Collection<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        final String sql = """
            INSERT INTO knowledge_chunks (
              id, tenant_id, project_id, meeting_id, document_id,
              source_type, source_id, source_segment_id,
              content, content_hash, embedding,
              chunk_version, transcript_version, minutes_version,
              chunk_strategy_version, embedding_model_version,
              status, stale_status, created_at, updated_at
            ) VALUES (
              ?, ?, ?, ?, ?,
              ?, ?, ?,
              ?, ?, ?::vector,
              ?, ?, ?,
              ?, ?,
              ?::content_status, ?::stale_status, ?, ?
            )
            ON CONFLICT (id) DO UPDATE SET
              project_id = EXCLUDED.project_id,
              meeting_id = EXCLUDED.meeting_id,
              document_id = EXCLUDED.document_id,
              source_type = EXCLUDED.source_type,
              source_id = EXCLUDED.source_id,
              source_segment_id = EXCLUDED.source_segment_id,
              content = EXCLUDED.content,
              content_hash = EXCLUDED.content_hash,
              embedding = EXCLUDED.embedding,
              chunk_version = EXCLUDED.chunk_version,
              transcript_version = EXCLUDED.transcript_version,
              minutes_version = EXCLUDED.minutes_version,
              chunk_strategy_version = EXCLUDED.chunk_strategy_version,
              embedding_model_version = EXCLUDED.embedding_model_version,
              status = EXCLUDED.status,
              stale_status = EXCLUDED.stale_status,
              updated_at = EXCLUDED.updated_at
            """;

        List<KnowledgeChunk> batch = new ArrayList<>(chunks);
        jdbcTemplate.batchUpdate(sql, batch, batch.size(), (ps, chunk) -> bindChunk(ps, chunk));
    }

    private static void bindChunk(PreparedStatement ps, KnowledgeChunk chunk) throws java.sql.SQLException {
        int i = 1;
        ps.setString(i++, chunk.id());
        ps.setString(i++, chunk.tenantId());
        setNullableString(ps, i++, chunk.projectId());
        setNullableString(ps, i++, chunk.meetingId());
        setNullableString(ps, i++, chunk.documentId());
        ps.setString(i++, chunk.sourceType().name());
        ps.setString(i++, chunk.sourceId());
        setNullableString(ps, i++, chunk.sourceSegmentId());
        ps.setString(i++, chunk.content());
        ps.setString(i++, chunk.contentHash());
        setNullableString(ps, i++, formatVector(chunk.embedding()));
        ps.setInt(i++, chunk.chunkVersion());
        setNullableInt(ps, i++, chunk.transcriptVersion());
        setNullableInt(ps, i++, chunk.minutesVersion());
        setNullableString(ps, i++, chunk.chunkStrategyVersion());
        setNullableString(ps, i++, chunk.embeddingModelVersion());
        ps.setString(i++, chunk.status().name());
        ps.setString(i++, chunk.staleStatus().name());
        ps.setTimestamp(i++, Timestamp.from(chunk.createdAt().toInstant()));
        ps.setTimestamp(i, Timestamp.from(chunk.updatedAt().toInstant()));
    }

    @Override
    public List<KnowledgeChunk> findByMeetingId(String tenantId, String meetingId) {
        return jdbcTemplate.query(
            selectByOwner("meeting_id"),
            (rs, n) -> mapRow(rs),
            tenantId, meetingId
        );
    }

    @Override
    public List<KnowledgeChunk> findByDocumentId(String tenantId, String documentId) {
        return jdbcTemplate.query(
            selectByOwner("document_id"),
            (rs, n) -> mapRow(rs),
            tenantId, documentId
        );
    }

    private static String selectByOwner(String ownerColumn) {
        return """
            SELECT id, tenant_id, project_id, meeting_id, document_id,
                   source_type, source_id, source_segment_id,
                   content, content_hash, embedding::text AS embedding,
                   chunk_version, transcript_version, minutes_version,
                   chunk_strategy_version, embedding_model_version,
                   status::text AS status, stale_status::text AS stale_status,
                   created_at, updated_at
              FROM knowledge_chunks
             WHERE tenant_id = ? AND %s = ?
             ORDER BY created_at ASC, id ASC
            """.formatted(ownerColumn);
    }

    private static KnowledgeChunk mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return KnowledgeChunk.builder()
            .id(rs.getString("id"))
            .tenantId(rs.getString("tenant_id"))
            .projectId(rs.getString("project_id"))
            .meetingId(rs.getString("meeting_id"))
            .documentId(rs.getString("document_id"))
            .sourceType(KnowledgeSourceType.valueOf(rs.getString("source_type")))
            .sourceId(rs.getString("source_id"))
            .sourceSegmentId(rs.getString("source_segment_id"))
            .content(rs.getString("content"))
            .contentHash(rs.getString("content_hash"))
            .embedding(parseVector(rs.getString("embedding")))
            .chunkVersion(rs.getInt("chunk_version"))
            .transcriptVersion((Integer) rs.getObject("transcript_version"))
            .minutesVersion((Integer) rs.getObject("minutes_version"))
            .chunkStrategyVersion(rs.getString("chunk_strategy_version"))
            .embeddingModelVersion(rs.getString("embedding_model_version"))
            .status(ChunkStatus.valueOf(rs.getString("status")))
            .staleStatus(StaleStatus.valueOf(rs.getString("stale_status")))
            .createdAt(rs.getObject("created_at", OffsetDateTime.class))
            .updatedAt(rs.getObject("updated_at", OffsetDateTime.class))
            .build();
    }

    @Override
    public int markEmbedding(String tenantId, String chunkId, float[] values, String modelVersion) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("markEmbedding values must be non-empty");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("markEmbedding modelVersion must be non-blank");
        }
        return jdbcTemplate.update(
            """
            UPDATE knowledge_chunks
               SET embedding = ?::vector,
                   embedding_model_version = ?,
                   stale_status = 'ACTIVE'::stale_status,
                   updated_at = now()
             WHERE tenant_id = ? AND id = ?
            """,
            formatVector(values),
            modelVersion,
            tenantId,
            chunkId
        );
    }

    @Override
    public int markEmbeddings(String tenantId, Map<String, EmbeddingResult> embeddingsByChunkId) {
        if (embeddingsByChunkId == null || embeddingsByChunkId.isEmpty()) {
            return 0;
        }
        final String sql = """
            UPDATE knowledge_chunks
               SET embedding = ?::vector,
                   embedding_model_version = ?,
                   stale_status = 'ACTIVE'::stale_status,
                   updated_at = now()
             WHERE tenant_id = ? AND id = ?
            """;
        List<Map.Entry<String, EmbeddingResult>> entries = new ArrayList<>(embeddingsByChunkId.entrySet());
        int[][] updateBatches = jdbcTemplate.batchUpdate(sql, entries, entries.size(), (ps, entry) -> {
            ps.setString(1, formatVector(entry.getValue().values()));
            ps.setString(2, entry.getValue().modelVersion());
            ps.setString(3, tenantId);
            ps.setString(4, entry.getKey());
        });
        int touched = 0;
        for (int[] batch : updateBatches) {
            for (int count : batch) {
                if (count > 0) {
                    touched += count;
                }
            }
        }
        return touched;
    }

    @Override
    public List<KnowledgeChunkCandidate> searchByVector(
        String tenantId, float[] queryVector, RetrievalScope scope, int topK
    ) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
            SELECT id, tenant_id, project_id, meeting_id, document_id,
                   source_type, source_id, source_segment_id, content,
                   transcript_version, minutes_version,
                   1 - (embedding <=> ?::vector) AS similarity
              FROM knowledge_chunks
             WHERE tenant_id = ?
               AND status = 'ACTIVE'::content_status
               AND stale_status = 'ACTIVE'::stale_status
               AND embedding IS NOT NULL
            """);
        List<Object> args = new ArrayList<>();
        args.add(formatVector(queryVector));
        args.add(tenantId);
        appendScopeFilter(sql, args, scope);
        sql.append(" ORDER BY embedding <=> ?::vector ASC LIMIT ?");
        args.add(formatVector(queryVector));
        args.add(topK);
        return jdbcTemplate.query(sql.toString(), (rs, n) -> mapCandidate(rs, "similarity"), args.toArray());
    }

    @Override
    public List<KnowledgeChunkCandidate> searchByKeyword(
        String tenantId, String queryText, RetrievalScope scope, int topK
    ) {
        if (queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
            SELECT id, tenant_id, project_id, meeting_id, document_id,
                   source_type, source_id, source_segment_id, content,
                   transcript_version, minutes_version,
                   ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS rank
              FROM knowledge_chunks
             WHERE tenant_id = ?
               AND status = 'ACTIVE'::content_status
               AND stale_status = 'ACTIVE'::stale_status
               AND to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
            """);
        List<Object> args = new ArrayList<>();
        args.add(queryText);
        args.add(tenantId);
        args.add(queryText);
        appendScopeFilter(sql, args, scope);
        sql.append(" ORDER BY rank DESC, id ASC LIMIT ?");
        args.add(topK);
        return jdbcTemplate.query(sql.toString(), (rs, n) -> mapCandidate(rs, "rank"), args.toArray());
    }

    private static void appendScopeFilter(StringBuilder sql, List<Object> args, RetrievalScope scope) {
        if (scope == null || scope.isEmpty()) {
            return;
        }
        List<String> ors = new ArrayList<>();
        if (!scope.meetingIds().isEmpty()) {
            ors.add("meeting_id IN (" + placeholders(scope.meetingIds().size()) + ")");
            args.addAll(scope.meetingIds());
        }
        if (!scope.documentIds().isEmpty()) {
            ors.add("document_id IN (" + placeholders(scope.documentIds().size()) + ")");
            args.addAll(scope.documentIds());
        }
        sql.append(" AND (").append(String.join(" OR ", ors)).append(")");
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private static KnowledgeChunkCandidate mapCandidate(ResultSet rs, String scoreColumn) throws SQLException {
        return new KnowledgeChunkCandidate(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("project_id"),
            rs.getString("meeting_id"),
            rs.getString("document_id"),
            KnowledgeSourceType.valueOf(rs.getString("source_type")),
            rs.getString("source_id"),
            rs.getString("source_segment_id"),
            rs.getString("content"),
            (Integer) rs.getObject("transcript_version"),
            (Integer) rs.getObject("minutes_version"),
            rs.getDouble(scoreColumn)
        );
    }

    @Override
    public int markStaleForMeeting(String tenantId, String meetingId) {
        return jdbcTemplate.update(
            """
            UPDATE knowledge_chunks
               SET stale_status = 'STALE'::stale_status, updated_at = now()
             WHERE tenant_id = ? AND meeting_id = ? AND stale_status = 'ACTIVE'
            """,
            tenantId,
            meetingId
        );
    }

    @Override
    public int markStaleForDocument(String tenantId, String documentId) {
        return jdbcTemplate.update(
            """
            UPDATE knowledge_chunks
               SET stale_status = 'STALE'::stale_status, updated_at = now()
             WHERE tenant_id = ? AND document_id = ? AND stale_status = 'ACTIVE'
            """,
            tenantId,
            documentId
        );
    }

    @Override
    public int updateStaleStatus(String tenantId, Collection<String> chunkIds, StaleStatus newStatus) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        List<String> ids = new ArrayList<>(chunkIds);
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        String sql = """
            UPDATE knowledge_chunks
               SET stale_status = ?::stale_status, updated_at = now()
             WHERE tenant_id = ? AND id IN (%s)
            """.formatted(placeholders);
        Object[] args = new Object[ids.size() + 2];
        args[0] = newStatus.name();
        args[1] = tenantId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 2] = ids.get(i);
        }
        return jdbcTemplate.update(sql, args);
    }

    // ── helpers ───────────────────────────────────────────────────

    private static void setNullableString(PreparedStatement ps, int idx, String value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, value);
        }
    }

    /** pgvector text format: {@code [v1,v2,...]} — used both on write (with ::vector cast) and parsed on read. Visible for tests. */
    public static String formatVector(float[] values) {
        if (values == null) return null;
        StringBuilder sb = new StringBuilder(values.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Visible for tests. */
    public static float[] parseVector(String text) {
        if (text == null || text.isBlank()) return null;
        String body = text.trim();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        if (body.isBlank()) return new float[0];
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }
}
