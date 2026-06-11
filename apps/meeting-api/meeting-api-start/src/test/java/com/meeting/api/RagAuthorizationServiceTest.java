package com.meeting.api;

import com.meeting.api.app.rag.RagAuthorizationService;
import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeChunkRepository.RetrievalScope;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import com.meeting.api.domain.rag.RagAuthorizationPort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagAuthorizationServiceTest {

    @Test
    void authorizeScopeReturnsFullAllowedWhenRequestIsEmpty() {
        var port = new FakeAuthzPort(
            new RetrievalScope(List.of("mtg_1", "mtg_2"), List.of("doc_1")),
            ReadableSnapshot.empty()
        );
        var svc = new RagAuthorizationService(port);

        RetrievalScope effective = svc.authorizeScope(
            "tenant_01", "user_01", RetrievalScope.EMPTY
        );

        assertThat(effective.meetingIds()).containsExactly("mtg_1", "mtg_2");
        assertThat(effective.documentIds()).containsExactly("doc_1");
    }

    @Test
    void authorizeScopeNarrowsToReadableSubset() {
        var port = new FakeAuthzPort(
            new RetrievalScope(List.of("mtg_1", "mtg_2"), List.of("doc_1")),
            ReadableSnapshot.empty()
        );
        var svc = new RagAuthorizationService(port);

        var requested = new RetrievalScope(List.of("mtg_1", "mtg_unauthorized", "mtg_2"), List.of("doc_other"));
        RetrievalScope effective = svc.authorizeScope("tenant_01", "user_01", requested);

        assertThat(effective.meetingIds()).containsExactly("mtg_1", "mtg_2");
        assertThat(effective.documentIds()).isEmpty();
    }

    @Test
    void authorizeScopeRejectsNullScope() {
        var svc = new RagAuthorizationService(new FakeAuthzPort(RetrievalScope.EMPTY, ReadableSnapshot.empty()));
        assertThatThrownBy(() -> svc.authorizeScope("t", "u", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filterAuthorizedDropsChunksAboveClearance() {
        var port = new FakeAuthzPort(
            RetrievalScope.EMPTY,
            ReadableSnapshot.allowing(Set.of("mtg_1"), Set.of("doc_1"))
        );
        var svc = new RagAuthorizationService(port);

        var candidates = List.of(
            candidate("ck_public", "mtg_1", null),
            candidate("ck_internal", "mtg_1", null),
            candidate("ck_confidential", "mtg_1", null),
            candidate("ck_secret", "mtg_1", null)
        );

        List<KnowledgeChunkCandidate> out = svc.filterAuthorized(
            "tenant_01", "user_01", candidates
        );

        assertThat(out).extracting(KnowledgeChunkCandidate::chunkId)
            .containsExactly("ck_public", "ck_internal");
    }

    @Test
    void filterAuthorizedDropsChunksWhoseOwnerIsUnreadable() {
        var port = new FakeAuthzPort(
            RetrievalScope.EMPTY,
            ReadableSnapshot.allowing(Set.of("mtg_visible"), Set.of("doc_visible"))
        );
        var svc = new RagAuthorizationService(port);

        var candidates = List.of(
            candidate("ck_mv", "mtg_visible", null),
            candidate("ck_mh", "mtg_hidden", null),
            candidate("ck_dv", null, "doc_visible"),
            candidate("ck_dh", null, "doc_hidden")
        );

        List<KnowledgeChunkCandidate> out = svc.filterAuthorized(
            "tenant_01", "user_01", candidates
        );

        assertThat(out).extracting(KnowledgeChunkCandidate::chunkId)
            .containsExactly("ck_mv", "ck_dv");
    }

    @Test
    void filterAuthorizedQueriesPortAtMostOnceWithMergedOwnerSet() {
        var port = new FakeAuthzPort(
            RetrievalScope.EMPTY,
            ReadableSnapshot.allowing(Set.of("mtg_a", "mtg_b"), Set.of("doc_a"))
        );
        var svc = new RagAuthorizationService(port);

        var candidates = List.of(
            candidate("ck_1", "mtg_a", null),
            candidate("ck_2", "mtg_a", null),
            candidate("ck_3", "mtg_b", null),
            candidate("ck_4", null, "doc_a")
        );

        svc.filterAuthorized("tenant_01", "user_01", candidates);

        assertThat(port.readableOwnersCalls).isEqualTo(1);
        assertThat(port.lastMeetingsQueried).containsExactlyInAnyOrder("mtg_a", "mtg_b");
        assertThat(port.lastDocumentsQueried).containsExactly("doc_a");
    }

    @Test
    void filterAuthorizedShortCircuitsOnEmptyInput() {
        var port = new FakeAuthzPort(RetrievalScope.EMPTY, ReadableSnapshot.empty());
        var svc = new RagAuthorizationService(port);

        assertThat(svc.filterAuthorized("t", "u", List.of())).isEmpty();
        assertThat(svc.filterAuthorized("t", "u", null)).isEmpty();
        assertThat(port.readableOwnersCalls).isEqualTo(0);
    }

    @Test
    void filterAuthorizedSkipsPortCallWhenAllCandidatesHaveNoOwner() {
        // edge case: candidates with neither meetingId nor documentId (shouldn't happen
        // per schema, but be defensive). Skip the per-owner check entirely.
        var port = new FakeAuthzPort(RetrievalScope.EMPTY, ReadableSnapshot.empty());
        var svc = new RagAuthorizationService(port);

        List<KnowledgeChunkCandidate> in = List.of(
            candidate("ck_orphan", null, null)
        );
        List<KnowledgeChunkCandidate> out = svc.filterAuthorized("t", "u", in);

        assertThat(out).extracting(KnowledgeChunkCandidate::chunkId).containsExactly("ck_orphan");
        assertThat(port.readableOwnersCalls).isEqualTo(0);
    }

    @Test
    void filterAuthorizedRejectsNullCandidates() {
        var svc = new RagAuthorizationService(new FakeAuthzPort(RetrievalScope.EMPTY, ReadableSnapshot.empty()));
        assertThatThrownBy(() -> svc.filterAuthorized("t", "u", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static KnowledgeChunkCandidate candidate(
        String id, String meetingId, String documentId
    ) {
        return new KnowledgeChunkCandidate(
            id, "tenant_01", null, meetingId, documentId,
            meetingId != null ? KnowledgeSourceType.PRIMARY_TRANSCRIPT : KnowledgeSourceType.DOCUMENT,
            "src_" + id, null, "content " + id,
            meetingId != null ? 1 : null, null, 0.5
        );
    }

    private record ReadableSnapshot(Set<String> meetings, Set<String> documents) {
        static ReadableSnapshot empty() {
            return new ReadableSnapshot(Set.of(), Set.of());
        }
        static ReadableSnapshot allowing(Set<String> meetings, Set<String> documents) {
            return new ReadableSnapshot(meetings, documents);
        }
    }

    private static final class FakeAuthzPort implements RagAuthorizationPort {
        private final RetrievalScope allowed;
        private final ReadableSnapshot snapshot;
        int readableOwnersCalls = 0;
        Set<String> lastMeetingsQueried = Set.of();
        Set<String> lastDocumentsQueried = Set.of();

        FakeAuthzPort(RetrievalScope allowed, ReadableSnapshot snapshot) {
            this.allowed = allowed;
            this.snapshot = snapshot;
        }

        @Override
        public RetrievalScope allowedScope(String tenantId, String userId) {
            return allowed;
        }

        @Override
        public ReadableOwners readableOwners(
            String tenantId, String userId,
            Set<String> meetingIds, Set<String> documentIds
        ) {
            readableOwnersCalls++;
            lastMeetingsQueried = new HashSet<>(meetingIds);
            lastDocumentsQueried = new HashSet<>(documentIds);
            Set<String> okMeetings = intersection(meetingIds, snapshot.meetings());
            Set<String> okDocs = intersection(documentIds, snapshot.documents());
            return new ReadableOwners(okMeetings, okDocs);
        }

        private static Set<String> intersection(Set<String> a, Set<String> b) {
            Set<String> out = new HashSet<>(a);
            out.retainAll(b);
            return out;
        }

        // Silence unused-field warning for collections we always re-init.
        @SuppressWarnings("unused")
        private List<String> _unused() { return new ArrayList<>(); }
    }
}
