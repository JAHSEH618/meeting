package com.meeting.api.infrastructure.persistence.rag;

import com.meeting.api.domain.rag.RagCitationEnricher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC implementation of {@link RagCitationEnricher}. Three small
 * batched lookups, one per owner type — used by
 * {@code RagQueryApplicationService} to enrich citation DTOs after the
 * second-pass authorization filter has narrowed the candidate set.
 *
 * <p>Every query is tenant-scoped via the explicit {@code tenant_id}
 * predicate (in addition to the {@code app.tenant_id} RLS policy that
 * the surrounding transaction has already set). Missing keys in the
 * returned maps mean the row was unreadable / deleted — callers
 * degrade gracefully rather than failing the whole query.
 *
 * <p>For transcript segments we pick the row with the highest
 * {@code transcript_version} per segment id: a chunk row indexed at
 * version <em>N</em> may already have been superseded by version
 * <em>N+1</em> at retrieval time; using the current view keeps the
 * citation in sync with what the UI will render.
 */
@Component
public class JdbcRagCitationEnricher implements RagCitationEnricher {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRagCitationEnricher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, String> loadMeetingTitles(String tenantId, Set<String> meetingIds) {
        if (meetingIds == null || meetingIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(meetingIds);
        String sql = "SELECT id, title FROM meetings "
            + "WHERE tenant_id = ? AND deleted_at IS NULL AND id IN (" + placeholders(ids.size()) + ")";
        Object[] args = withTenant(tenantId, ids);
        Map<String, String> out = new HashMap<>(ids.size());
        jdbcTemplate.query(sql, args, rs -> {
            out.put(rs.getString("id"), rs.getString("title"));
        });
        return out;
    }

    @Override
    public Map<String, String> loadDocumentTitles(String tenantId, Set<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(documentIds);
        String sql = "SELECT id, title FROM documents "
            + "WHERE tenant_id = ? AND deleted_at IS NULL AND id IN (" + placeholders(ids.size()) + ")";
        Object[] args = withTenant(tenantId, ids);
        Map<String, String> out = new HashMap<>(ids.size());
        jdbcTemplate.query(sql, args, rs -> {
            out.put(rs.getString("id"), rs.getString("title"));
        });
        return out;
    }

    @Override
    public Map<String, TranscriptSegmentInfo> loadTranscriptSegments(String tenantId, Set<String> segmentIds) {
        if (segmentIds == null || segmentIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(segmentIds);
        // Latest transcript_version wins via DISTINCT ON — the chunk's
        // transcript_version may be older than the current row but the
        // citation should reflect what the UI shows today.
        String sql = "SELECT DISTINCT ON (id) "
            + "  id, speaker_label, speaker_name, start_ms, end_ms "
            + "FROM transcript_segments "
            + "WHERE tenant_id = ? AND id IN (" + placeholders(ids.size()) + ") "
            + "ORDER BY id, transcript_version DESC";
        Object[] args = withTenant(tenantId, ids);
        Map<String, TranscriptSegmentInfo> out = new HashMap<>(ids.size());
        jdbcTemplate.query(sql, args, rs -> {
            out.put(rs.getString("id"), new TranscriptSegmentInfo(
                rs.getString("id"),
                rs.getString("speaker_label"),
                rs.getString("speaker_name"),
                rs.getLong("start_ms"),
                rs.getLong("end_ms")
            ));
        });
        return out;
    }

    @Override
    public Map<String, Integer> loadDocumentChunkPages(String tenantId, Set<String> documentChunkIds) {
        if (documentChunkIds == null || documentChunkIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(documentChunkIds);
        String sql = "SELECT id, page_number FROM document_chunks "
            + "WHERE tenant_id = ? AND id IN (" + placeholders(ids.size()) + ")";
        Object[] args = withTenant(tenantId, ids);
        Map<String, Integer> out = new HashMap<>(ids.size());
        jdbcTemplate.query(sql, args, rs -> {
            int page = rs.getInt("page_number");
            if (!rs.wasNull()) {
                out.put(rs.getString("id"), page);
            }
        });
        return out;
    }

    private static Object[] withTenant(String tenantId, List<String> ids) {
        Object[] args = new Object[1 + ids.size()];
        args[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) {
            args[1 + i] = ids.get(i);
        }
        return args;
    }

    private static String placeholders(int n) {
        if (n <= 0) return "NULL";
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }
}
