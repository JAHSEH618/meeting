package com.meeting.api.app.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.RagAnswerCoverage;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.rag.DocumentChunkCitationDTO;
import com.meeting.api.client.rag.MeetingSegmentCitationDTO;
import com.meeting.api.client.rag.RagAnswerDTO;
import com.meeting.api.client.rag.RagCitationDTO;
import com.meeting.api.client.rag.RagQueryCommand;
import com.meeting.api.client.rag.RagQueryFacade;
import com.meeting.api.client.rag.RagQueryScope;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import com.meeting.api.domain.rag.EmbeddingGateway;
import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository.RetrievalScope;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import com.meeting.api.domain.rag.RagCitationEnricher;
import com.meeting.api.domain.rag.RagCitationEnricher.TranscriptSegmentInfo;
import com.meeting.api.domain.rag.RerankGateway;
import com.meeting.api.domain.rag.RrfFusion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the RAG query pipeline behind {@code POST /api/rag/query}.
 *
 * <p>The end-to-end flow follows the contract in {@code CLAUDE.md} §4:
 * pgvector + tsvector are <em>candidate retrievers</em> only; the
 * authoritative read-time permission check happens here, in Java,
 * after retrieval. The LLM is never called with chunks the user is not
 * authorized to read.
 *
 * <ol>
 *   <li>Authorize the requested scope ({@link RagAuthorizationService#authorizeScope}).</li>
 *   <li>Embed the question via {@link EmbeddingGateway} (single-text batch).</li>
 *   <li>Run vector + keyword retrieval in {@link KnowledgeChunkRepository}
 *       under the authorized scope, fetching {@code retrievalTopK}
 *       candidates per channel.</li>
 *   <li>RRF-fuse the two channels into a single ranked list.</li>
 *   <li>Second-pass filter with
 *       {@link RagAuthorizationService#filterAuthorized} (drops chunks
 *       above clearance or whose owner became unreadable since indexing).</li>
 *   <li>Rerank up to {@code rerankCandidatePoolSize} survivors via
 *       {@link RerankGateway}. If ai-worker is unavailable we degrade
 *       to RRF order and log a metric — contract failures still throw.</li>
 *   <li>Build a numbered context block out of the top-N reranked chunks
 *       and call the LLM through {@link LlmGateway} under the most
 *       restrictive security level among the cited chunks — the gateway
 *       fails closed on {@code CONFIDENTIAL}/{@code SECRET}.</li>
 *   <li>Parse the LLM's JSON output, map cited indices back to chunk
 *       citations, and stamp the response with the audit id from
 *       {@code llm_call_logs}.</li>
 * </ol>
 *
 * <p>Returns a degraded answer ("no information") with empty citations
 * when retrieval / authorization yields no chunks — the LLM is not
 * called in that case, which keeps the read-time policy fail-closed.
 */
@Service
public class RagQueryApplicationService implements RagQueryFacade {

    private static final Logger log = LoggerFactory.getLogger(RagQueryApplicationService.class);

    private static final String LLM_CAPABILITY = "RAG_QUERY";
    private static final String LLM_TASK_NAME = "rag_answer_zh";
    private static final String DEGRADED_ANSWER_NO_CHUNKS =
        "根据现有信息无法回答：未检索到任何符合权限范围的内容。";

    private final TenantScopedTransaction tenantScopedTransaction;
    private final RagAuthorizationService authorizationService;
    private final EmbeddingGateway embeddingGateway;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final RerankGateway rerankGateway;
    private final LlmGateway llmGateway;
    private final RagCitationEnricher citationEnricher;
    private final RagAnswerCache answerCache;
    private final ObjectMapper objectMapper;
    private final MeetingApiMetrics metrics;
    private final MeterRegistry meterRegistry;
    private final int retrievalTopK;
    private final int rerankCandidatePoolSize;
    private final int rrfK;

    public RagQueryApplicationService(
        TenantScopedTransaction tenantScopedTransaction,
        RagAuthorizationService authorizationService,
        EmbeddingGateway embeddingGateway,
        KnowledgeChunkRepository knowledgeChunkRepository,
        RerankGateway rerankGateway,
        LlmGateway llmGateway,
        RagCitationEnricher citationEnricher,
        RagAnswerCache answerCache,
        ObjectMapper objectMapper,
        MeetingApiMetrics metrics,
        MeterRegistry meterRegistry,
        @Value("${meeting.rag.query.retrieval-top-k:50}") int retrievalTopK,
        @Value("${meeting.rag.query.rerank-pool-size:50}") int rerankCandidatePoolSize,
        @Value("${meeting.rag.query.rrf-k:" + RrfFusion.DEFAULT_K + "}") int rrfK
    ) {
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.authorizationService = authorizationService;
        this.embeddingGateway = embeddingGateway;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.rerankGateway = rerankGateway;
        this.llmGateway = llmGateway;
        this.citationEnricher = citationEnricher;
        this.answerCache = answerCache;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.meterRegistry = meterRegistry;
        this.retrievalTopK = retrievalTopK;
        this.rerankCandidatePoolSize = rerankCandidatePoolSize;
        this.rrfK = rrfK;
    }

    /**
     * Test-only convenience constructor that wires a private
     * {@link SimpleMeterRegistry} so suites which don't care about
     * metrics can keep their previous 9-arg signature. Production
     * code-paths must use the 14-arg constructor so Prometheus picks
     * up phase timers.
     */
    public RagQueryApplicationService(
        TenantScopedTransaction tenantScopedTransaction,
        RagAuthorizationService authorizationService,
        EmbeddingGateway embeddingGateway,
        KnowledgeChunkRepository knowledgeChunkRepository,
        RerankGateway rerankGateway,
        LlmGateway llmGateway,
        RagCitationEnricher citationEnricher,
        RagAnswerCache answerCache,
        ObjectMapper objectMapper,
        int retrievalTopK,
        int rerankCandidatePoolSize,
        int rrfK
    ) {
        this(
            tenantScopedTransaction, authorizationService, embeddingGateway,
            knowledgeChunkRepository, rerankGateway, llmGateway, citationEnricher,
            answerCache, objectMapper,
            new MeetingApiMetrics(new SimpleMeterRegistry()),
            new SimpleMeterRegistry(),
            retrievalTopK, rerankCandidatePoolSize, rrfK
        );
    }

    @Override
    public RagAnswerDTO query(RagQueryCommand command) {
        // RagQueryCommand validates non-blank/non-null in its compact ctor.
        RagAnswerCache.RagCacheKey cacheKey = toCacheKey(command);
        var cached = answerCache.lookup(cacheKey);
        if (cached.isPresent()) {
            log.info(
                "rag_query_cache_hit tenant={} user={} citations={} coverage={}",
                command.tenantId(), command.userId(),
                cached.get().citations().size(), cached.get().coverage()
            );
            return cached.get();
        }
        return tenantScopedTransaction.execute(
            command.tenantId(), command.userId(), command.requestId(),
            () -> {
                RagAnswerDTO answer = doQuery(command);
                // Don't cache the degraded "no information" answer: a
                // subsequent reindex / permission change should be
                // observable without waiting for the TTL.
                if (answer.artifactManifestId() != null) {
                    answerCache.store(cacheKey, answer, coverageOf(answer));
                }
                return answer;
            }
        );
    }

    private RagAnswerDTO doQuery(RagQueryCommand command) {
        String tenantId = command.tenantId();
        String userId = command.userId();
        SecurityLevel clearance = command.clearance();

        Timer.Sample authorizeSample = Timer.start(meterRegistry);
        RetrievalScope authorizedScope;
        try {
            authorizedScope = authorizationService.authorizeScope(
                tenantId, userId, clearance, toRetrievalScope(command.scope())
            );
        } finally {
            authorizeSample.stop(metrics.ragQueryPhaseTimer("authorize"));
        }

        // If the caller supplied an explicit scope but lost every meeting / document
        // through authorization, fail closed without embedding or retrieving.
        if (!command.scope().isEmpty() && authorizedScope.isEmpty()) {
            log.info(
                "rag_query_unauthorized_scope tenant={} user={} requestedMeetings={} requestedDocuments={}",
                tenantId, userId,
                command.scope().meetingIds().size(), command.scope().documentIds().size()
            );
            return degraded(DEGRADED_ANSWER_NO_CHUNKS);
        }

        Timer.Sample embedSample = Timer.start(meterRegistry);
        EmbeddingGateway.EmbedResult embedded;
        try {
            embedded = embeddingGateway.embed(new EmbeddingGateway.EmbedRequest(
                tenantId, List.of(command.question()), command.requestId(), command.traceId()
            ));
        } finally {
            embedSample.stop(metrics.ragQueryPhaseTimer("embed"));
        }
        if (embedded.vectors().isEmpty()) {
            throw new IllegalStateException("EmbeddingGateway returned no vector for the query");
        }
        float[] queryVector = embedded.vectors().get(0);

        Timer.Sample retrieveSample = Timer.start(meterRegistry);
        List<KnowledgeChunkCandidate> vectorRanked;
        List<KnowledgeChunkCandidate> keywordRanked;
        List<KnowledgeChunkCandidate> fused;
        try {
            vectorRanked = knowledgeChunkRepository.searchByVector(
                tenantId, queryVector, authorizedScope, retrievalTopK
            );
            keywordRanked = knowledgeChunkRepository.searchByKeyword(
                tenantId, command.question(), authorizedScope, retrievalTopK
            );
            fused = RrfFusion.fuse(vectorRanked, keywordRanked, rrfK);
        } finally {
            retrieveSample.stop(metrics.ragQueryPhaseTimer("retrieve"));
        }
        if (fused.isEmpty()) {
            log.info("rag_query_empty_retrieval tenant={} user={} vec=0 kw=0", tenantId, userId);
            return degraded(DEGRADED_ANSWER_NO_CHUNKS);
        }

        Timer.Sample authzFilterSample = Timer.start(meterRegistry);
        List<KnowledgeChunkCandidate> authorized;
        try {
            authorized = authorizationService.filterAuthorized(
                tenantId, userId, clearance, fused
            );
        } finally {
            authzFilterSample.stop(metrics.ragQueryPhaseTimer("authorize_filter"));
        }
        if (authorized.isEmpty()) {
            log.info(
                "rag_query_empty_after_authz tenant={} user={} fused={}",
                tenantId, userId, fused.size()
            );
            return degraded(DEGRADED_ANSWER_NO_CHUNKS);
        }

        // Bound the pool fed to ai-worker; rerank cost is O(N) on the GPU.
        List<KnowledgeChunkCandidate> rerankPool = authorized.size() > rerankCandidatePoolSize
            ? authorized.subList(0, rerankCandidatePoolSize)
            : authorized;

        Timer.Sample rerankSample = Timer.start(meterRegistry);
        List<KnowledgeChunkCandidate> ordered;
        try {
            ordered = rerankOrFallback(command, rerankPool);
        } finally {
            rerankSample.stop(metrics.ragQueryPhaseTimer("rerank"));
        }

        int topN = Math.min(command.topN(), ordered.size());
        List<KnowledgeChunkCandidate> top = ordered.subList(0, topN);

        Timer.Sample citeSample = Timer.start(meterRegistry);
        EnrichedCitations enriched;
        String contextBlock;
        try {
            enriched = enrich(tenantId, top);
            contextBlock = renderContext(top, enriched);
        } finally {
            citeSample.stop(metrics.ragQueryPhaseTimer("cite"));
        }

        // The LLM is called under the highest security level surfaced in the
        // citations — that way DashScopeLlmGateway can fail closed if any
        // CONFIDENTIAL / SECRET chunk slipped through (it shouldn't, since
        // filterAuthorized drops them, but defense in depth is cheap here).
        SecurityLevel effectiveLevel = highestSecurityLevel(top);
        String firstMeetingId = top.stream()
            .map(KnowledgeChunkCandidate::meetingId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("query", command.question());
        variables.put("retrievedChunks", contextBlock);

        Timer.Sample llmSample = Timer.start(meterRegistry);
        LlmGateway.LlmResponse response;
        try {
            response = llmGateway.complete(new LlmGateway.LlmRequest(
                tenantId,
                firstMeetingId,
                null,
                LLM_CAPABILITY,
                LLM_TASK_NAME,
                effectiveLevel,
                variables,
                null,
                command.traceId()
            ));
        } catch (LlmProviderException ex) {
            log.warn(
                "rag_query_llm_failed tenant={} user={} errorCode={} msg={}",
                tenantId, userId, ex.errorCode(), ex.getMessage()
            );
            throw ex;
        } finally {
            llmSample.stop(metrics.ragQueryPhaseTimer("llm"));
        }

        ParsedAnswer parsed = parseAnswer(
            response.structuredJson() != null ? response.structuredJson() : response.content(),
            top.size()
        );

        List<RagCitationDTO> citations = mapCitations(parsed.citationIndices(), top, enriched);
        RagAnswerCoverage coverage = computeCoverage(citations, top);

        log.info(
            "rag_query_done tenant={} user={} vectorHits={} keywordHits={} fused={} authorized={} top={} citations={} coverage={} llmLog={}",
            tenantId, userId, vectorRanked.size(), keywordRanked.size(), fused.size(),
            authorized.size(), top.size(), citations.size(), coverage, response.llmCallLogId()
        );

        return new RagAnswerDTO(parsed.answer(), citations, coverage, response.artifactManifestId());
    }

    private List<KnowledgeChunkCandidate> rerankOrFallback(
        RagQueryCommand command, List<KnowledgeChunkCandidate> pool
    ) {
        List<RerankGateway.RerankCandidate> rerankInputs = new ArrayList<>(pool.size());
        for (KnowledgeChunkCandidate c : pool) {
            rerankInputs.add(new RerankGateway.RerankCandidate(
                c.chunkId(),
                c.sourceType() == null ? null : c.sourceType().name(),
                c.content(),
                c.score(),
                c.transcriptVersion() != null ? c.transcriptVersion() : c.minutesVersion()
            ));
        }
        try {
            RerankGateway.RerankResult result = rerankGateway.rerank(new RerankGateway.RerankRequest(
                command.tenantId(),
                command.question(),
                rerankInputs,
                Math.min(command.topN(), pool.size()),
                null,
                command.requestId(),
                command.traceId()
            ));
            Map<String, KnowledgeChunkCandidate> byId = new HashMap<>();
            for (KnowledgeChunkCandidate c : pool) {
                byId.put(c.chunkId(), c);
            }
            List<RerankGateway.RankedItem> items = new ArrayList<>(result.items());
            items.sort(java.util.Comparator.comparingInt(RerankGateway.RankedItem::rank));
            List<KnowledgeChunkCandidate> ordered = new ArrayList<>(items.size());
            for (RerankGateway.RankedItem ranked : items) {
                KnowledgeChunkCandidate c = byId.get(ranked.chunkId());
                if (c != null) {
                    ordered.add(c);
                }
            }
            return ordered;
        } catch (AiWorkerUnavailableException ex) {
            log.warn(
                "rag_rerank_degraded tenant={} user={} reason={} — falling back to RRF order",
                command.tenantId(), command.userId(), ex.getMessage()
            );
            return pool;
        }
    }

    private EnrichedCitations enrich(String tenantId, List<KnowledgeChunkCandidate> top) {
        Set<String> meetingIds = new HashSet<>();
        Set<String> documentIds = new HashSet<>();
        Set<String> segmentIds = new HashSet<>();
        Set<String> documentChunkIds = new HashSet<>();
        for (KnowledgeChunkCandidate c : top) {
            if (c.meetingId() != null) meetingIds.add(c.meetingId());
            if (c.documentId() != null) documentIds.add(c.documentId());
            if (isMeetingType(c.sourceType()) && c.sourceSegmentId() != null) {
                segmentIds.add(c.sourceSegmentId());
            }
            if (c.sourceType() == KnowledgeSourceType.DOCUMENT && c.sourceId() != null) {
                String docChunkId = stripSubindex(c.sourceId());
                if (docChunkId != null) {
                    documentChunkIds.add(docChunkId);
                }
            }
        }

        Map<String, String> meetingTitles = meetingIds.isEmpty()
            ? Map.of() : citationEnricher.loadMeetingTitles(tenantId, meetingIds);
        Map<String, String> documentTitles = documentIds.isEmpty()
            ? Map.of() : citationEnricher.loadDocumentTitles(tenantId, documentIds);
        Map<String, TranscriptSegmentInfo> segmentInfo = segmentIds.isEmpty()
            ? Map.of() : citationEnricher.loadTranscriptSegments(tenantId, segmentIds);
        Map<String, Integer> chunkPages = documentChunkIds.isEmpty()
            ? Map.of() : citationEnricher.loadDocumentChunkPages(tenantId, documentChunkIds);

        return new EnrichedCitations(meetingTitles, documentTitles, segmentInfo, chunkPages);
    }

    private static boolean isMeetingType(KnowledgeSourceType type) {
        return type != null && type != KnowledgeSourceType.DOCUMENT;
    }

    private static String stripSubindex(String sourceId) {
        if (sourceId == null) return null;
        int hash = sourceId.indexOf('#');
        return hash < 0 ? sourceId : sourceId.substring(0, hash);
    }

    private static String renderContext(List<KnowledgeChunkCandidate> top, EnrichedCitations enriched) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            KnowledgeChunkCandidate c = top.get(i);
            int idx = i + 1;
            sb.append("[").append(idx).append("] ");
            if (c.sourceType() == KnowledgeSourceType.DOCUMENT) {
                String title = enriched.documentTitles().getOrDefault(c.documentId(), "(未知文档)");
                String docChunkId = stripSubindex(c.sourceId());
                Integer page = docChunkId == null ? null : enriched.documentChunkPages().get(docChunkId);
                sb.append("文档『").append(title).append("』");
                if (page != null) {
                    sb.append(" 第").append(page).append("页");
                }
            } else {
                String title = enriched.meetingTitles().getOrDefault(c.meetingId(), "(未知会议)");
                TranscriptSegmentInfo seg = c.sourceSegmentId() == null
                    ? null
                    : enriched.segmentInfo().get(c.sourceSegmentId());
                sb.append("会议『").append(title).append("』");
                if (seg != null) {
                    sb.append(" ").append(seg.displaySpeaker())
                        .append(" @ ").append(formatTimestamp(seg.startMs()));
                }
            }
            sb.append("\n").append(c.content()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String formatTimestamp(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0
            ? String.format("%d:%02d:%02d", h, m, s)
            : String.format("%d:%02d", m, s);
    }

    private List<RagCitationDTO> mapCitations(
        List<Integer> indices, List<KnowledgeChunkCandidate> top, EnrichedCitations enriched
    ) {
        if (indices.isEmpty()) {
            return List.of();
        }
        // Preserve LLM order, drop duplicates, drop out-of-range.
        List<RagCitationDTO> out = new ArrayList<>(indices.size());
        Set<Integer> seen = new HashSet<>();
        for (int oneBased : indices) {
            if (!seen.add(oneBased)) continue;
            int zeroBased = oneBased - 1;
            if (zeroBased < 0 || zeroBased >= top.size()) {
                log.warn("rag_query_citation_out_of_range index={} topSize={}", oneBased, top.size());
                continue;
            }
            KnowledgeChunkCandidate c = top.get(zeroBased);
            out.add(toCitation(c, enriched));
        }
        return out;
    }

    private static RagCitationDTO toCitation(KnowledgeChunkCandidate c, EnrichedCitations enriched) {
        if (c.sourceType() == KnowledgeSourceType.DOCUMENT) {
            String title = enriched.documentTitles().getOrDefault(c.documentId(), "");
            String docChunkId = stripSubindex(c.sourceId());
            Integer page = docChunkId == null ? null : enriched.documentChunkPages().get(docChunkId);
            return new DocumentChunkCitationDTO(
                c.chunkId(),
                c.documentId(),
                title,
                page == null ? 0 : page,
                c.content()
            );
        }
        String title = enriched.meetingTitles().getOrDefault(c.meetingId(), "");
        TranscriptSegmentInfo seg = c.sourceSegmentId() == null
            ? null
            : enriched.segmentInfo().get(c.sourceSegmentId());
        return new MeetingSegmentCitationDTO(
            c.chunkId(),
            c.meetingId(),
            title,
            c.sourceSegmentId() == null ? "" : c.sourceSegmentId(),
            seg == null ? "" : seg.displaySpeaker(),
            seg == null ? 0L : seg.startMs(),
            seg == null ? 0L : seg.endMs(),
            c.content()
        );
    }

    private static RagAnswerCoverage computeCoverage(
        List<RagCitationDTO> citations, List<KnowledgeChunkCandidate> top
    ) {
        // Prefer evidence the LLM actually cited; fall back to retrieved
        // chunks if the LLM produced an answer without explicit citations.
        if (citations.isEmpty()) {
            return anyDocument(top) ? RagAnswerCoverage.FULL : RagAnswerCoverage.TRANSCRIPT_ONLY;
        }
        for (RagCitationDTO citation : citations) {
            if (citation instanceof DocumentChunkCitationDTO) {
                return RagAnswerCoverage.FULL;
            }
        }
        return RagAnswerCoverage.TRANSCRIPT_ONLY;
    }

    private static boolean anyDocument(List<KnowledgeChunkCandidate> chunks) {
        for (KnowledgeChunkCandidate c : chunks) {
            if (c.sourceType() == KnowledgeSourceType.DOCUMENT) {
                return true;
            }
        }
        return false;
    }

    private static SecurityLevel highestSecurityLevel(List<KnowledgeChunkCandidate> chunks) {
        SecurityLevel max = SecurityLevel.PUBLIC;
        for (KnowledgeChunkCandidate c : chunks) {
            if (c.securityLevel() != null && c.securityLevel().ordinal() > max.ordinal()) {
                max = c.securityLevel();
            }
        }
        return max;
    }

    private ParsedAnswer parseAnswer(String llmOutput, int topSize) {
        if (llmOutput == null || llmOutput.isBlank()) {
            throw new LlmProviderException(
                ErrorCode.LLM_SCHEMA_INVALID, "RAG LLM returned an empty answer"
            );
        }
        try {
            JsonNode root = objectMapper.readTree(llmOutput);
            String answer = root.has("answer") && !root.get("answer").isNull()
                ? root.get("answer").asText()
                : llmOutput;
            List<Integer> citations = new ArrayList<>();
            JsonNode citationsNode = root.get("citations");
            if (citationsNode != null && citationsNode.isArray()) {
                for (JsonNode node : citationsNode) {
                    int idx = -1;
                    if (node.isInt()) {
                        idx = node.asInt();
                    } else if (node.isObject() && node.has("index") && node.get("index").isInt()) {
                        idx = node.get("index").asInt();
                    }
                    if (idx >= 1 && idx <= topSize) {
                        citations.add(idx);
                    }
                }
            }
            return new ParsedAnswer(answer, citations);
        } catch (JsonProcessingException ex) {
            // The template asks for JSON, but be defensive: treat raw markdown
            // as the answer body with no machine-readable citations.
            log.warn("rag_query_llm_output_not_json — using raw output as answer");
            return new ParsedAnswer(llmOutput, List.of());
        }
    }

    private static RetrievalScope toRetrievalScope(RagQueryScope scope) {
        if (scope == null || scope.isEmpty()) {
            return RetrievalScope.EMPTY;
        }
        return new RetrievalScope(scope.meetingIds(), scope.documentIds());
    }

    private static RagAnswerCache.RagCacheKey toCacheKey(RagQueryCommand command) {
        return new RagAnswerCache.RagCacheKey(
            command.tenantId(), command.userId(), command.clearance(),
            command.question(), command.scope(), command.topN(), command.includeStale()
        );
    }

    private static RagAnswerCache.CacheCoverage coverageOf(RagAnswerDTO answer) {
        Set<String> meetingIds = new HashSet<>();
        Set<String> documentIds = new HashSet<>();
        for (RagCitationDTO c : answer.citations()) {
            if (c instanceof MeetingSegmentCitationDTO m) {
                meetingIds.add(m.meetingId());
            } else if (c instanceof DocumentChunkCitationDTO d) {
                documentIds.add(d.documentId());
            }
        }
        return new RagAnswerCache.CacheCoverage(meetingIds, documentIds);
    }

    private static RagAnswerDTO degraded(String answer) {
        return new RagAnswerDTO(answer, List.of(), RagAnswerCoverage.TRANSCRIPT_ONLY, null);
    }

    /** Bundled lookup result so we only walk the chunk list once. */
    private record EnrichedCitations(
        Map<String, String> meetingTitles,
        Map<String, String> documentTitles,
        Map<String, TranscriptSegmentInfo> segmentInfo,
        Map<String, Integer> documentChunkPages
    ) {
    }

    private record ParsedAnswer(String answer, List<Integer> citationIndices) {
    }
}
