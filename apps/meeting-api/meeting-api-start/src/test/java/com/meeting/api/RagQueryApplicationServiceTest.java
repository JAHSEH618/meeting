package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.rag.RagAuthorizationService;
import com.meeting.api.app.rag.RagQueryApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.RagAnswerCoverage;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.rag.DocumentChunkCitationDTO;
import com.meeting.api.client.rag.MeetingSegmentCitationDTO;
import com.meeting.api.client.rag.RagAnswerDTO;
import com.meeting.api.client.rag.RagQueryCommand;
import com.meeting.api.client.rag.RagQueryScope;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.domain.rag.EmbeddingGateway;
import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository.RetrievalScope;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import com.meeting.api.domain.rag.RagAuthorizationPort;
import com.meeting.api.domain.rag.RagCitationEnricher;
import com.meeting.api.domain.rag.RerankGateway;
import com.meeting.api.domain.rag.RrfFusion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagQueryApplicationServiceTest {

    private static final String TENANT = "tenant_01";
    private static final String USER = "user_01";

    private FakeAuthzPort authzPort;
    private FakeEmbeddingGateway embeddingGateway;
    private FakeKnowledgeChunkRepository chunkRepository;
    private FakeRerankGateway rerankGateway;
    private FakeLlmGateway llmGateway;
    private FakeCitationEnricher enricher;
    private com.meeting.api.app.rag.InMemoryRagAnswerCache cache;
    private RagQueryApplicationService service;

    @BeforeEach
    void setUp() {
        authzPort = new FakeAuthzPort();
        embeddingGateway = new FakeEmbeddingGateway();
        chunkRepository = new FakeKnowledgeChunkRepository();
        rerankGateway = new FakeRerankGateway();
        llmGateway = new FakeLlmGateway();
        enricher = new FakeCitationEnricher();
        cache = new com.meeting.api.app.rag.InMemoryRagAnswerCache(
            java.time.Clock.systemUTC(), 300L, 1024
        );
        service = new RagQueryApplicationService(
            TenantScopedTransaction.immediate(),
            new RagAuthorizationService(authzPort),
            embeddingGateway,
            chunkRepository,
            rerankGateway,
            llmGateway,
            enricher,
            cache,
            new ObjectMapper(),
            50, 50, RrfFusion.DEFAULT_K
        );
    }

    @Test
    void happyPathReturnsRichCitationsAndCoverageFullWhenDocumentCited() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of("doc_a")));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of("doc_a"));

        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "Alice 在第一次会议讨论了路线图", 0.9, SecurityLevel.INTERNAL),
            documentChunk("ck2", "doc_a", "dc_1#0", "路线图设计文档第3页", 0.8, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "Alice 在第一次会议讨论了路线图", 0.6, SecurityLevel.INTERNAL),
            documentChunk("ck2", "doc_a", "dc_1#0", "路线图设计文档第3页", 0.5, SecurityLevel.INTERNAL)
        );
        rerankGateway.respondPreservingOrder();

        enricher.meetingTitles.put("mtg_a", "Q3 Planning");
        enricher.documentTitles.put("doc_a", "Roadmap.pdf");
        enricher.segmentInfo.put("seg_1", new RagCitationEnricher.TranscriptSegmentInfo(
            "seg_1", "S1", "Alice", 12_000L, 25_000L
        ));
        enricher.documentChunkPages.put("dc_1", 3);

        llmGateway.respond("{\"answer\":\"基于会议与文档的综合回答\",\"citations\":[1,2]}");

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "路线图是什么？", RagQueryScope.EMPTY,
            5, false, "req_1", "trace_1"
        ));

        assertThat(out.answer()).isEqualTo("基于会议与文档的综合回答");
        assertThat(out.coverage()).isEqualTo(RagAnswerCoverage.FULL);
        assertThat(out.artifactManifestId()).isEqualTo("llmlog_fake");
        assertThat(out.citations()).hasSize(2);

        var first = (MeetingSegmentCitationDTO) out.citations().get(0);
        assertThat(first.chunkId()).isEqualTo("ck1");
        assertThat(first.meetingTitle()).isEqualTo("Q3 Planning");
        assertThat(first.speaker()).isEqualTo("Alice");
        assertThat(first.startMs()).isEqualTo(12_000L);
        assertThat(first.endMs()).isEqualTo(25_000L);

        var second = (DocumentChunkCitationDTO) out.citations().get(1);
        assertThat(second.chunkId()).isEqualTo("ck2");
        assertThat(second.documentTitle()).isEqualTo("Roadmap.pdf");
        assertThat(second.page()).isEqualTo(3);
    }

    @Test
    void coverageTranscriptOnlyWhenNoDocumentCitations() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());

        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "transcript content", 0.9, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "Stand-up");

        llmGateway.respond("{\"answer\":\"transcript-grounded answer\",\"citations\":[1]}");

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "what was decided?", RagQueryScope.EMPTY,
            5, false, "req_2", "trace_2"
        ));

        assertThat(out.coverage()).isEqualTo(RagAnswerCoverage.TRANSCRIPT_ONLY);
        assertThat(out.citations()).hasSize(1);
        assertThat(out.citations().get(0)).isInstanceOf(MeetingSegmentCitationDTO.class);
    }

    @Test
    void degradesWhenRetrievalReturnsNothing() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        chunkRepository.vectorReturns(List.of());
        chunkRepository.keywordReturns(List.of());

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "anything?", RagQueryScope.EMPTY,
            5, false, "req_3", "trace_3"
        ));

        assertThat(out.answer()).contains("无法回答");
        assertThat(out.citations()).isEmpty();
        assertThat(out.artifactManifestId()).isNull();
        assertThat(llmGateway.callCount.get()).isEqualTo(0);
    }

    @Test
    void degradesWhenScopeWasFullyUnauthorized() {
        // user requested mtg_x but it's not in their allowed scope at all
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q",
            new RagQueryScope(List.of("mtg_x"), List.of()),
            5, false, "req_4", "trace_4"
        ));

        assertThat(out.answer()).contains("无法回答");
        assertThat(out.citations()).isEmpty();
        assertThat(embeddingGateway.callCount.get()).isEqualTo(0);
        assertThat(llmGateway.callCount.get()).isEqualTo(0);
    }

    @Test
    void degradesWhenSecondPassFiltersEverything() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        // readableOwners will drop the chunk's meeting
        authzPort.allowReadable(Set.of(), Set.of());

        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "internal content", 0.9, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            5, false, "req_5", "trace_5"
        ));

        assertThat(out.answer()).contains("无法回答");
        assertThat(out.citations()).isEmpty();
        assertThat(llmGateway.callCount.get()).isEqualTo(0);
    }

    @Test
    void rerankUnavailableFallsBackToRrfOrderAndStillCallsLlm() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck_top", "mtg_a", "seg_1", "vector-top hit", 0.95, SecurityLevel.INTERNAL),
            meetingChunk("ck_low", "mtg_a", "seg_2", "lower hit", 0.50, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.unavailable();
        enricher.meetingTitles.put("mtg_a", "M");

        llmGateway.respond("{\"answer\":\"ok\",\"citations\":[1]}");

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            2, false, "req_6", "trace_6"
        ));

        assertThat(out.answer()).isEqualTo("ok");
        assertThat(out.citations()).hasSize(1);
        var c = (MeetingSegmentCitationDTO) out.citations().get(0);
        assertThat(c.chunkId()).isEqualTo("ck_top");
    }

    @Test
    void highSecurityChunksFilteredOutBeforeReachingLlm() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());

        // user clearance INTERNAL but a SECRET chunk slipped into retrieval
        chunkRepository.vectorReturns(
            meetingChunk("ck_internal", "mtg_a", "seg_1", "internal", 0.9, SecurityLevel.INTERNAL),
            meetingChunk("ck_secret", "mtg_a", "seg_2", "secret leak", 0.95, SecurityLevel.SECRET)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");

        llmGateway.respond("{\"answer\":\"ok\",\"citations\":[1]}");

        service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            5, false, "req_7", "trace_7"
        ));

        // The LLM saw only the internal chunk in its prompt.
        assertThat(llmGateway.lastVariables.get("retrievedChunks").toString())
            .contains("internal").doesNotContain("secret leak");
    }

    @Test
    void llmSecurityLevelIsHighestAmongCitedChunks() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "public chunk", 0.8, SecurityLevel.PUBLIC),
            meetingChunk("ck2", "mtg_a", "seg_2", "internal chunk", 0.7, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");
        llmGateway.respond("{\"answer\":\"ok\",\"citations\":[]}");

        service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            5, false, "req_8", "trace_8"
        ));

        assertThat(llmGateway.lastSecurityLevel).isEqualTo(SecurityLevel.INTERNAL);
    }

    @Test
    void invalidLlmOutputJsonIsTreatedAsRawAnswerWithoutCitations() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "x", 0.5, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");
        llmGateway.respond("this is not json");

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            5, false, "req_9", "trace_9"
        ));

        assertThat(out.answer()).isEqualTo("this is not json");
        assertThat(out.citations()).isEmpty();
    }

    @Test
    void emptyLlmAnswerSurfacesAsSchemaInvalid() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "x", 0.5, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");
        llmGateway.respond("");

        assertThatThrownBy(() -> service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            5, false, "req_10", "trace_10"
        ))).isInstanceOfSatisfying(LlmProviderException.class, ex ->
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.LLM_SCHEMA_INVALID));
    }

    @Test
    void citationsOutOfRangeAreDroppedNotPropagated() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "only chunk", 0.9, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");

        // LLM hallucinates index 99 plus duplicate 1
        llmGateway.respond("{\"answer\":\"a\",\"citations\":[1,99,1]}");

        RagAnswerDTO out = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            5, false, "req_11", "trace_11"
        ));

        assertThat(out.citations()).hasSize(1);
    }

    @Test
    void commandRejectsInvalidInputs() {
        assertThatThrownBy(() -> new RagQueryCommand(
            "", USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY, 5, false, "r", "t"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "  ", RagQueryScope.EMPTY, 5, false, "r", "t"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagQueryCommand(
            TENANT, USER, null, "q", RagQueryScope.EMPTY, 5, false, "r", "t"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY, 0, false, "r", "t"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY, 21, false, "r", "t"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rerankInputCarriesRrfScoreAndSourceType() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "content", 0.9, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");
        llmGateway.respond("{\"answer\":\"a\",\"citations\":[]}");

        service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "q", RagQueryScope.EMPTY,
            5, false, "req_12", "trace_12"
        ));

        assertThat(rerankGateway.lastCandidates).hasSize(1);
        var rc = rerankGateway.lastCandidates.get(0);
        assertThat(rc.chunkId()).isEqualTo("ck1");
        assertThat(rc.sourceType()).isEqualTo(KnowledgeSourceType.PRIMARY_TRANSCRIPT.name());
        assertThat(rc.text()).isEqualTo("content");
        // After RRF fusion the score is the fused value, not the raw retrieval similarity.
        assertThat(rc.rrfScore()).isPositive();
    }

    @Test
    void secondInvocationOfTheSameQuestionServesFromCache() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "content", 0.9, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");
        llmGateway.respond("{\"answer\":\"cached body\",\"citations\":[1]}");

        var cmd = new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "shared question", RagQueryScope.EMPTY,
            5, false, "req_a", "trace_a"
        );
        service.query(cmd);
        int callsAfterFirst = llmGateway.callCount.get();
        int embedCallsAfterFirst = embeddingGateway.callCount.get();

        // Second call with same identity + question — should hit the cache and skip the LLM.
        var out2 = service.query(new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "shared question", RagQueryScope.EMPTY,
            5, false, "req_b", "trace_b"
        ));

        assertThat(out2.answer()).isEqualTo("cached body");
        assertThat(llmGateway.callCount.get()).isEqualTo(callsAfterFirst);
        assertThat(embeddingGateway.callCount.get()).isEqualTo(embedCallsAfterFirst);
    }

    @Test
    void reindexEventInvalidatesAffectedCacheEntry() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "content", 0.9, SecurityLevel.INTERNAL)
        );
        chunkRepository.keywordReturns(List.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");
        llmGateway.respond("{\"answer\":\"stale\",\"citations\":[1]}");

        var cmd = new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "what?", RagQueryScope.EMPTY,
            5, false, "req_a", "trace_a"
        );
        service.query(cmd);

        // Simulate a reindex of mtg_a — the cache entry should be dropped.
        cache.onChunkReindex(new com.meeting.api.app.rag.KnowledgeChunkReindexRequestedEvent(
            TENANT, "mtg_a", null, List.of(),
            SecurityLevel.INTERNAL, "v1", 1, null, null
        ));

        llmGateway.respond("{\"answer\":\"fresh\",\"citations\":[1]}");
        var out = service.query(cmd);
        assertThat(out.answer()).isEqualTo("fresh");
    }

    @Test
    void degradedAnswersAreNotCached() {
        authzPort.setAllowed(new RetrievalScope(List.of("mtg_a"), List.of()));
        chunkRepository.vectorReturns(List.of());
        chunkRepository.keywordReturns(List.of());

        var cmd = new RagQueryCommand(
            TENANT, USER, SecurityLevel.INTERNAL, "none?", RagQueryScope.EMPTY,
            5, false, "req_a", "trace_a"
        );
        var first = service.query(cmd);
        assertThat(first.artifactManifestId()).isNull();

        // Make a fresh LLM-backed answer possible for the next call.
        chunkRepository.vectorReturns(
            meetingChunk("ck1", "mtg_a", "seg_1", "now there's content", 0.9, SecurityLevel.INTERNAL)
        );
        authzPort.allowReadable(Set.of("mtg_a"), Set.of());
        rerankGateway.respondPreservingOrder();
        enricher.meetingTitles.put("mtg_a", "M");
        llmGateway.respond("{\"answer\":\"real answer\",\"citations\":[1]}");

        var second = service.query(cmd);
        // Cache MUST NOT have served the degraded answer.
        assertThat(second.answer()).isEqualTo("real answer");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static KnowledgeChunkCandidate meetingChunk(
        String id, String meetingId, String segmentId, String content,
        double score, SecurityLevel level
    ) {
        return new KnowledgeChunkCandidate(
            id, TENANT, null, meetingId, null,
            KnowledgeSourceType.PRIMARY_TRANSCRIPT, meetingId, segmentId, content,
            level, 1, null, score
        );
    }

    private static KnowledgeChunkCandidate documentChunk(
        String id, String documentId, String sourceId, String content,
        double score, SecurityLevel level
    ) {
        return new KnowledgeChunkCandidate(
            id, TENANT, null, null, documentId,
            KnowledgeSourceType.DOCUMENT, sourceId, null, content,
            level, null, null, score
        );
    }

    // ── Fakes ──────────────────────────────────────────────────────────

    private static final class FakeAuthzPort implements RagAuthorizationPort {
        private RetrievalScope allowed = RetrievalScope.EMPTY;
        private Set<String> readableMeetings = Set.of();
        private Set<String> readableDocuments = Set.of();

        void setAllowed(RetrievalScope s) { this.allowed = s; }

        void allowReadable(Set<String> meetings, Set<String> documents) {
            this.readableMeetings = meetings;
            this.readableDocuments = documents;
        }

        @Override
        public RetrievalScope allowedScope(String tenantId, String userId, SecurityLevel clearance) {
            return allowed;
        }

        @Override
        public ReadableOwners readableOwners(
            String tenantId, String userId, SecurityLevel clearance,
            Set<String> meetingIds, Set<String> documentIds
        ) {
            Set<String> okM = new HashSet<>(meetingIds);
            okM.retainAll(readableMeetings);
            Set<String> okD = new HashSet<>(documentIds);
            okD.retainAll(readableDocuments);
            return new ReadableOwners(okM, okD);
        }
    }

    private static final class FakeEmbeddingGateway implements EmbeddingGateway {
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public EmbedResult embed(EmbedRequest request) {
            callCount.incrementAndGet();
            float[] v = new float[]{1f, 0f, 0f};
            return new EmbedResult("bge-m3-fake", 3, List.of(v));
        }
    }

    private static final class FakeKnowledgeChunkRepository implements KnowledgeChunkRepository {
        private List<KnowledgeChunkCandidate> vector = List.of();
        private List<KnowledgeChunkCandidate> keyword = List.of();

        void vectorReturns(KnowledgeChunkCandidate... cs) { this.vector = List.of(cs); }
        void vectorReturns(List<KnowledgeChunkCandidate> cs) { this.vector = cs; }
        void keywordReturns(KnowledgeChunkCandidate... cs) { this.keyword = List.of(cs); }
        void keywordReturns(List<KnowledgeChunkCandidate> cs) { this.keyword = cs; }

        @Override public int markStaleForMeeting(String tenantId, String meetingId) { return 0; }

        @Override
        public List<KnowledgeChunkCandidate> searchByVector(
            String tenantId, float[] queryVector, RetrievalScope scope, int topK
        ) {
            return vector;
        }

        @Override
        public List<KnowledgeChunkCandidate> searchByKeyword(
            String tenantId, String queryText, RetrievalScope scope, int topK
        ) {
            return keyword;
        }
    }

    private static final class FakeRerankGateway implements RerankGateway {
        private boolean unavailable = false;
        List<RerankCandidate> lastCandidates = List.of();

        void respondPreservingOrder() { this.unavailable = false; }
        void unavailable() { this.unavailable = true; }

        @Override
        public RerankResult rerank(RerankRequest request) {
            if (unavailable) {
                throw new AiWorkerUnavailableException("RERANK_UNAVAILABLE", "fake");
            }
            this.lastCandidates = request.candidates();
            List<RankedItem> items = new ArrayList<>();
            int rank = 1;
            for (RerankCandidate c : request.candidates()) {
                items.add(new RankedItem(c.chunkId(), rank, 1.0 / rank));
                rank++;
            }
            return new RerankResult("bge-reranker-v2-m3-fake", items);
        }
    }

    private static final class FakeLlmGateway implements LlmGateway {
        final AtomicInteger callCount = new AtomicInteger();
        Map<String, Object> lastVariables = Map.of();
        SecurityLevel lastSecurityLevel;
        private String responseBody = "{\"answer\":\"\",\"citations\":[]}";

        void respond(String json) { this.responseBody = json; }

        @Override
        public LlmResponse complete(LlmRequest request) {
            callCount.incrementAndGet();
            this.lastVariables = new HashMap<>(request.variables());
            this.lastSecurityLevel = request.securityLevel();
            return new LlmResponse(responseBody, null, 0, 0, 0L, "qwen-plus-fake", "llmlog_fake");
        }
    }

    private static final class FakeCitationEnricher implements RagCitationEnricher {
        final Map<String, String> meetingTitles = new HashMap<>();
        final Map<String, String> documentTitles = new HashMap<>();
        final Map<String, TranscriptSegmentInfo> segmentInfo = new HashMap<>();
        final Map<String, Integer> documentChunkPages = new HashMap<>();

        @Override
        public Map<String, String> loadMeetingTitles(String tenantId, Set<String> meetingIds) {
            Map<String, String> out = new HashMap<>();
            for (String id : meetingIds) {
                if (meetingTitles.containsKey(id)) out.put(id, meetingTitles.get(id));
            }
            return out;
        }

        @Override
        public Map<String, String> loadDocumentTitles(String tenantId, Set<String> documentIds) {
            Map<String, String> out = new HashMap<>();
            for (String id : documentIds) {
                if (documentTitles.containsKey(id)) out.put(id, documentTitles.get(id));
            }
            return out;
        }

        @Override
        public Map<String, TranscriptSegmentInfo> loadTranscriptSegments(String tenantId, Set<String> segmentIds) {
            Map<String, TranscriptSegmentInfo> out = new HashMap<>();
            for (String id : segmentIds) {
                if (segmentInfo.containsKey(id)) out.put(id, segmentInfo.get(id));
            }
            return out;
        }

        @Override
        public Map<String, Integer> loadDocumentChunkPages(String tenantId, Set<String> documentChunkIds) {
            Map<String, Integer> out = new HashMap<>();
            for (String id : documentChunkIds) {
                if (documentChunkPages.containsKey(id)) out.put(id, documentChunkPages.get(id));
            }
            return out;
        }
    }
}
