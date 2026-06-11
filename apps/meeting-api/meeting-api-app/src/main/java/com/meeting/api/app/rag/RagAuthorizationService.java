package com.meeting.api.app.rag;

import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeChunkRepository.RetrievalScope;
import com.meeting.api.domain.rag.RagAuthorizationPort;
import com.meeting.api.domain.rag.RagAuthorizationPort.ReadableOwners;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Second-pass authorization for RAG retrieval. Enforces the invariant
 * from {@code CLAUDE.md} §4:
 *
 * <blockquote>RAG permissions computed live by Java. pgvector is a
 * candidate retriever only — never the permission authority. Retrieved
 * chunks are filtered by a second-pass PostgreSQL permission check
 * before reranking.</blockquote>
 *
 * <p>The service has two distinct responsibilities, called at different
 * points in the {@code RagQueryApplicationService} pipeline:
 *
 * <ol>
 *   <li>{@link #authorizeScope} narrows the caller-requested scope to
 *       what the user actually has access to, returning a
 *       {@link RetrievalScope} that becomes the SQL pre-filter for
 *       vector + keyword search.</li>
 *   <li>{@link #filterAuthorized} runs against the candidate list AFTER
 *       retrieval — drops chunks whose owner the user can no longer read
 *       (e.g. a meeting was deleted between retrieval and this check).</li>
 * </ol>
 *
 * <p>Both methods are pure functions of the port — no caching, no
 * application-level state, no DB writes.
 */
@Service
public class RagAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(RagAuthorizationService.class);

    private final RagAuthorizationPort port;

    public RagAuthorizationService(RagAuthorizationPort port) {
        this.port = port;
    }

    /**
     * Compute the effective scope to use for retrieval. If the caller
     * did not specify a scope, returns the user's full readable scope.
     * Otherwise narrows the caller's request to the readable subset; an
     * unauthorized request yields {@link RetrievalScope#EMPTY} (which
     * the retrieval layer treats as "no rows" rather than "unrestricted").
     */
    public RetrievalScope authorizeScope(
        String tenantId, String userId, RetrievalScope requested
    ) {
        RetrievalScope allowed = port.allowedScope(tenantId, userId);
        if (requested == null || requested.isEmpty()) {
            return allowed;
        }
        List<String> meetings = intersect(requested.meetingIds(), allowed.meetingIds());
        List<String> documents = intersect(requested.documentIds(), allowed.documentIds());
        if (meetings.size() < requested.meetingIds().size()
            || documents.size() < requested.documentIds().size()) {
            log.info(
                "rag_scope_narrowed tenant={} user={} requestedMeetings={} allowedMeetings={} "
                    + "requestedDocuments={} allowedDocuments={}",
                tenantId, userId, requested.meetingIds().size(), meetings.size(),
                requested.documentIds().size(), documents.size()
            );
        }
        return new RetrievalScope(meetings, documents);
    }

    /**
     * Filter a retrieved candidate list to those the user can read right
     * now. Drops candidates whose owning meeting or document is no
     * longer readable (deleted, moved out of scope, …).
     *
     * The batched query keeps this O(1) round-trip even on
     * large candidate lists.
     */
    public List<KnowledgeChunkCandidate> filterAuthorized(
        String tenantId, String userId,
        List<KnowledgeChunkCandidate> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<KnowledgeChunkCandidate> cleared = new ArrayList<>(candidates.size());
        Set<String> meetingIds = new HashSet<>();
        Set<String> documentIds = new HashSet<>();
        for (KnowledgeChunkCandidate c : candidates) {
            cleared.add(c);
            if (c.meetingId() != null) meetingIds.add(c.meetingId());
            if (c.documentId() != null) documentIds.add(c.documentId());
        }
        if (cleared.isEmpty()) {
            return List.of();
        }
        if (meetingIds.isEmpty() && documentIds.isEmpty()) {
            // No owner to check — every candidate had only chunk-level membership.
            return cleared;
        }

        ReadableOwners readable = port.readableOwners(tenantId, userId, meetingIds, documentIds);

        List<KnowledgeChunkCandidate> out = new ArrayList<>(cleared.size());
        for (KnowledgeChunkCandidate c : cleared) {
            boolean meetingOk = c.meetingId() == null || readable.meetingIds().contains(c.meetingId());
            boolean documentOk = c.documentId() == null || readable.documentIds().contains(c.documentId());
            if (meetingOk && documentOk) {
                out.add(c);
            }
        }
        if (out.size() < cleared.size()) {
            log.info(
                "rag_chunks_filtered tenant={} user={} retrieved={} clearedBySecurity={} authorized={}",
                tenantId, userId, candidates.size(), cleared.size(), out.size()
            );
        }
        return out;
    }

    private static List<String> intersect(List<String> requested, Set<String> allowed) {
        List<String> out = new ArrayList<>(Math.min(requested.size(), allowed.size()));
        for (String id : requested) {
            if (allowed.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static List<String> intersect(List<String> requested, List<String> allowed) {
        return intersect(requested, new HashSet<>(allowed));
    }
}
