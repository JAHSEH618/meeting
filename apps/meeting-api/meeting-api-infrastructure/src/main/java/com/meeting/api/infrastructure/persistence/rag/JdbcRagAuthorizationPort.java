package com.meeting.api.infrastructure.persistence.rag;

import com.meeting.api.domain.rag.KnowledgeChunkRepository.RetrievalScope;
import com.meeting.api.domain.rag.RagAuthorizationPort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase-1 implementation of {@link RagAuthorizationPort}. The visible
 * scope is computed live from the {@code meetings} / {@code documents}
 * tables using a simple policy that mirrors how the rest of the API
 * authorizes reads today:
 *
 * <ul>
 *   <li>tenant isolation is implicit (RLS + explicit {@code tenant_id = ?}),</li>
 *   <li>a user can read any non-deleted resource they created.</li>
 * </ul>
 *
 * <p>This is intentionally permissive for phase 1 — fine-grained
 * sharing / role-based access lands later. The spec explicitly forbids
 * using the {@code knowledge_chunk_acl} materialized table as the
 * source of truth (it is a future cache only), which is why this
 * implementation queries the underlying meeting / document rows.
 */
@Component
public class JdbcRagAuthorizationPort implements RagAuthorizationPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRagAuthorizationPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RetrievalScope allowedScope(String tenantId, String userId) {
        List<String> meetingIds = jdbcTemplate.query(
            "SELECT id FROM meetings "
                + " WHERE tenant_id = ? AND deleted_at IS NULL "
                + "   AND created_by = ?",
            (rs, n) -> rs.getString("id"),
            tenantId, userId
        );
        List<String> documentIds = jdbcTemplate.query(
            "SELECT id FROM documents "
                + " WHERE tenant_id = ? AND deleted_at IS NULL "
                + "   AND created_by = ?",
            (rs, n) -> rs.getString("id"),
            tenantId, userId
        );
        return new RetrievalScope(meetingIds, documentIds);
    }

    @Override
    public ReadableOwners readableOwners(
        String tenantId, String userId,
        Set<String> meetingIds, Set<String> documentIds
    ) {
        Set<String> okMeetings = (meetingIds == null || meetingIds.isEmpty())
            ? Set.of()
            : new HashSet<>(queryReadableOwners("meetings", tenantId, userId, meetingIds));
        Set<String> okDocuments = (documentIds == null || documentIds.isEmpty())
            ? Set.of()
            : new HashSet<>(queryReadableOwners("documents", tenantId, userId, documentIds));
        return new ReadableOwners(okMeetings, okDocuments);
    }

    private List<String> queryReadableOwners(
        String table, String tenantId, String userId, Set<String> ownerIds
    ) {
        List<String> owners = new ArrayList<>(ownerIds);
        String idPlaceholders = placeholders(owners.size());
        String sql = "SELECT id FROM " + table
            + " WHERE tenant_id = ? AND deleted_at IS NULL "
            + "   AND id IN (" + idPlaceholders + ") "
            + "   AND created_by = ?";
        Object[] args = new Object[1 + owners.size() + 1];
        int i = 0;
        args[i++] = tenantId;
        for (String id : owners) args[i++] = id;
        args[i++] = userId;
        return jdbcTemplate.query(sql, (rs, n) -> rs.getString("id"), args);
    }

    private static String placeholders(int n) {
        if (n <= 0) return "NULL";
        StringBuilder sb = new StringBuilder(n * 2);
        for (int j = 0; j < n; j++) {
            if (j > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }
}
