package com.meeting.api.domain.rag;

import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.rag.KnowledgeChunkRepository.RetrievalScope;
import java.util.Set;

/**
 * Port that the second-pass RAG authorization layer queries to learn
 * which meetings + documents a user is allowed to read at retrieval time.
 *
 * <p>The infrastructure implementation lives in
 * {@code JdbcRagAuthorizationPort} and computes membership live from the
 * {@code meetings} / {@code documents} tables — phase 1 deliberately
 * <em>does not</em> use the {@code knowledge_chunk_acl} cache as the
 * source of truth (the spec calls that table a future optimization).
 *
 * <p>Two methods, each handling a different point in the RAG flow:
 *
 * <ol>
 *   <li>{@link #allowedScope} pre-computes the user's visible meetings +
 *       documents so the SQL retrieval can pre-filter by them (the
 *       "first-pass" / metadata filter).</li>
 *   <li>{@link #readableOwners} accepts the (meetingIds, documentIds)
 *       carried by retrieved candidates and returns the subset the user
 *       can actually read — the authoritative "second-pass" check that
 *       guards against stale cache + index drift.</li>
 * </ol>
 */
public interface RagAuthorizationPort {

    /**
     * Compute the full set of meetings + documents the user can read in
     * the given tenant, capped at {@code clearance}. Used to seed the
     * {@link RetrievalScope} for vector / keyword retrieval when the
     * caller did not specify one.
     */
    RetrievalScope allowedScope(String tenantId, String userId, SecurityLevel clearance);

    /**
     * Return the subset of the candidate (meetingIds, documentIds) the
     * user is actually authorized to read right now. Owners not in the
     * input are not returned. The implementation must hit the DB —
     * caches are not allowed at this point per spec §12.3.
     */
    ReadableOwners readableOwners(
        String tenantId,
        String userId,
        SecurityLevel clearance,
        Set<String> meetingIds,
        Set<String> documentIds
    );

    /** Snapshot of which meeting + document IDs the user is allowed to read. */
    record ReadableOwners(Set<String> meetingIds, Set<String> documentIds) {
        public static final ReadableOwners EMPTY = new ReadableOwners(Set.of(), Set.of());

        public ReadableOwners {
            if (meetingIds == null) meetingIds = Set.of();
            if (documentIds == null) documentIds = Set.of();
        }
    }
}
