package com.meeting.api.app.rag;

import com.meeting.api.domain.document.DocumentChunkRepository;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.minutes.MinutesRepository;
import com.meeting.api.domain.rag.ChunkStrategy;
import com.meeting.api.domain.rag.KnowledgeChunk;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-chunks a meeting or document into rows on {@code knowledge_chunks},
 * the upstream of RAG retrieval. Each invocation:
 *
 * <ol>
 *   <li>Marks the existing chunks for that meeting / document as
 *       {@code STALE} (so the next query skips them until the new
 *       chunks are embedded).</li>
 *   <li>Pulls the source-of-truth rows: transcript segments + minutes
 *       sections / items + accepted action items / decisions / risks
 *       for meetings, or pre-parsed document_chunks for documents.</li>
 *   <li>Splits each source row by the configured {@link ChunkStrategy}
 *       (default: 512-char Chinese sliding window with 64 overlap).</li>
 *   <li>Persists the new chunks with {@code embedding=NULL} so the
 *       async TEXT_EMBEDDING task picks them up (the M5A C11 dispatcher
 *       polls for chunks that need an embedding).</li>
 * </ol>
 *
 * <p>The whole flow runs in a single transaction so retry / rollback
 * leaves no half-stale meeting. Outbox eventing is deferred to M5A C11.
 */
@Service
public class ChunkingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingApplicationService.class);

    private final TranscriptRepository transcriptRepository;
    private final MinutesRepository minutesRepository;
    private final ActionItemRepository actionItemRepository;
    private final DecisionRepository decisionRepository;
    private final RiskRepository riskRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final MeetingRepository meetingRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final ChunkStrategy strategy;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ChunkingApplicationService(
        TranscriptRepository transcriptRepository,
        MinutesRepository minutesRepository,
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        DocumentRepository documentRepository,
        DocumentChunkRepository documentChunkRepository,
        MeetingRepository meetingRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        ApplicationEventPublisher eventPublisher,
        Clock clock
    ) {
        this(transcriptRepository, minutesRepository, actionItemRepository,
            decisionRepository, riskRepository, documentRepository,
            documentChunkRepository, meetingRepository, knowledgeChunkRepository,
            ChunkStrategy.DEFAULT_ZH, eventPublisher, clock);
    }

    public ChunkingApplicationService(
        TranscriptRepository transcriptRepository,
        MinutesRepository minutesRepository,
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        DocumentRepository documentRepository,
        DocumentChunkRepository documentChunkRepository,
        MeetingRepository meetingRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        ChunkStrategy strategy,
        ApplicationEventPublisher eventPublisher,
        Clock clock
    ) {
        this.transcriptRepository = transcriptRepository;
        this.minutesRepository = minutesRepository;
        this.actionItemRepository = actionItemRepository;
        this.decisionRepository = decisionRepository;
        this.riskRepository = riskRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.meetingRepository = meetingRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.strategy = strategy;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public ChunkingResult rebuildForMeeting(String tenantId, String meetingId) {
        Meeting meeting = meetingRepository.findById(tenantId, meetingId)
            .orElseThrow(() -> new IllegalArgumentException(
                "meeting not found: tenantId=" + tenantId + " meetingId=" + meetingId));

        int stale = knowledgeChunkRepository.markStaleForMeeting(tenantId, meetingId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<KnowledgeChunk> built = new ArrayList<>();

        int transcriptVersion = transcriptRepository.currentTranscriptVersion(tenantId, meetingId);
        if (transcriptVersion > 0) {
            for (var seg : transcriptRepository.findByMeeting(tenantId, meetingId, transcriptVersion)) {
                String text = currentText(seg.currentText(), seg.editedText(), seg.originalText());
                if (text == null || text.isBlank()) {
                    continue;
                }
                int sub = 0;
                for (String piece : split(text)) {
                    built.add(KnowledgeChunk.builder()
                        .id(newChunkId("trn"))
                        .tenantId(tenantId)
                        .meetingId(meetingId)
                        .sourceType(KnowledgeSourceType.PRIMARY_TRANSCRIPT)
                        .sourceId(seg.segmentId() + "#" + sub)
                        .sourceSegmentId(seg.segmentId())
                        .content(piece)
                        .contentHash(sha256(piece))
                        .chunkStrategyVersion(strategy.name())
                        .transcriptVersion(transcriptVersion)
                        .securityLevel(meeting.securityLevel())
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                    sub++;
                }
            }
        }

        int minutesVersion = 0;
        var minutesOpt = minutesRepository.findCurrent(tenantId, meetingId);
        if (minutesOpt.isPresent()) {
            var minutes = minutesOpt.get();
            minutesVersion = minutes.minutesVersion();
            int sectionIdx = 0;
            for (var section : minutes.sections()) {
                int itemIdx = 0;
                for (var item : section.items()) {
                    if (item.text() == null || item.text().isBlank()) {
                        itemIdx++;
                        continue;
                    }
                    int sub = 0;
                    for (String piece : split(item.text())) {
                        built.add(KnowledgeChunk.builder()
                            .id(newChunkId("min"))
                            .tenantId(tenantId)
                            .meetingId(meetingId)
                            .sourceType(KnowledgeSourceType.MINUTES)
                            .sourceId(minutes.id() + ":sec_" + sectionIdx + ":itm_" + itemIdx + "#" + sub)
                            .content(piece)
                            .contentHash(sha256(piece))
                            .chunkStrategyVersion(strategy.name())
                            .transcriptVersion(transcriptVersion > 0 ? transcriptVersion : null)
                            .minutesVersion(minutesVersion)
                            .securityLevel(meeting.securityLevel())
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                        sub++;
                    }
                    itemIdx++;
                }
                sectionIdx++;
            }
        }

        for (var ai : actionItemRepository.findByMeeting(tenantId, meetingId)) {
            chunkExtraction(built, tenantId, meeting, KnowledgeSourceType.ACTION_ITEM,
                ai.id(), ai.title(), ai.description(), ai.acceptanceStatus(),
                ai.sourceTranscriptVersion(), minutesVersion, now);
        }
        for (var dec : decisionRepository.findByMeeting(tenantId, meetingId)) {
            chunkExtraction(built, tenantId, meeting, KnowledgeSourceType.DECISION,
                dec.id(), dec.title(), dec.description(), dec.acceptanceStatus(),
                dec.sourceTranscriptVersion(), minutesVersion, now);
        }
        for (var risk : riskRepository.findByMeeting(tenantId, meetingId)) {
            chunkExtraction(built, tenantId, meeting, KnowledgeSourceType.RISK,
                risk.id(), risk.title(), risk.description(), risk.acceptanceStatus(),
                risk.sourceTranscriptVersion(), minutesVersion, now);
        }

        knowledgeChunkRepository.saveAll(built);

        List<KnowledgeChunkReindexRequestedEvent.ChunkRef> refs = built.stream()
            .map(c -> new KnowledgeChunkReindexRequestedEvent.ChunkRef(c.id(), c.content()))
            .toList();
        List<String> newChunkIds = refs.stream().map(KnowledgeChunkReindexRequestedEvent.ChunkRef::id).toList();
        log.info(
            "chunking rebuilt meeting={} stale={} new={} strategy={} transcriptV={} minutesV={}",
            meetingId, stale, built.size(), strategy.name(), transcriptVersion, minutesVersion
        );
        publishReindexEvent(new KnowledgeChunkReindexRequestedEvent(
            tenantId, meetingId, null, refs,
            meeting.securityLevel(), strategy.name(),
            transcriptVersion > 0 ? transcriptVersion : null,
            minutesVersion > 0 ? minutesVersion : null,
            null
        ));
        return new ChunkingResult(stale, newChunkIds);
    }

    @Transactional
    public ChunkingResult rebuildForDocument(String tenantId, String documentId) {
        var doc = documentRepository.findById(tenantId, documentId)
            .orElseThrow(() -> new IllegalArgumentException(
                "document not found: tenantId=" + tenantId + " documentId=" + documentId));

        int stale = knowledgeChunkRepository.markStaleForDocument(tenantId, documentId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<KnowledgeChunk> built = new ArrayList<>();

        for (var src : documentChunkRepository.findByDocument(tenantId, documentId)) {
            if (src.content() == null || src.content().isBlank()) {
                continue;
            }
            int sub = 0;
            for (String piece : split(src.content())) {
                built.add(KnowledgeChunk.builder()
                    .id(newChunkId("doc"))
                    .tenantId(tenantId)
                    .documentId(documentId)
                    .sourceType(KnowledgeSourceType.DOCUMENT)
                    .sourceId(src.id() + "#" + sub)
                    .content(piece)
                    .contentHash(sha256(piece))
                    .chunkStrategyVersion(strategy.name())
                    .securityLevel(doc.securityLevel())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
                sub++;
            }
        }

        knowledgeChunkRepository.saveAll(built);

        List<KnowledgeChunkReindexRequestedEvent.ChunkRef> refs = built.stream()
            .map(c -> new KnowledgeChunkReindexRequestedEvent.ChunkRef(c.id(), c.content()))
            .toList();
        List<String> newChunkIds = refs.stream().map(KnowledgeChunkReindexRequestedEvent.ChunkRef::id).toList();
        log.info(
            "chunking rebuilt document={} stale={} new={} strategy={}",
            documentId, stale, built.size(), strategy.name()
        );
        publishReindexEvent(new KnowledgeChunkReindexRequestedEvent(
            tenantId, null, documentId, refs,
            doc.securityLevel(), strategy.name(),
            null, null, null
        ));
        return new ChunkingResult(stale, newChunkIds);
    }

    private void publishReindexEvent(KnowledgeChunkReindexRequestedEvent event) {
        if (eventPublisher == null || !event.hasWork()) {
            return;
        }
        eventPublisher.publishEvent(event);
    }

    private void chunkExtraction(
        List<KnowledgeChunk> sink,
        String tenantId,
        Meeting meeting,
        KnowledgeSourceType type,
        String itemId,
        String title,
        String description,
        String acceptanceStatus,
        Integer sourceTranscriptVersion,
        int minutesVersion,
        OffsetDateTime now
    ) {
        // Skip explicitly rejected items — they should not surface in RAG.
        if ("REJECTED".equalsIgnoreCase(acceptanceStatus)) {
            return;
        }
        String body = composeExtractionBody(title, description);
        if (body.isBlank()) {
            return;
        }
        int sub = 0;
        for (String piece : split(body)) {
            sink.add(KnowledgeChunk.builder()
                .id(newChunkId(type.name().toLowerCase(Locale.ROOT).substring(0, 3)))
                .tenantId(tenantId)
                .meetingId(meeting.id())
                .sourceType(type)
                .sourceId(itemId + "#" + sub)
                .content(piece)
                .contentHash(sha256(piece))
                .chunkStrategyVersion(strategy.name())
                .transcriptVersion(sourceTranscriptVersion)
                .minutesVersion(minutesVersion > 0 ? minutesVersion : null)
                .securityLevel(meeting.securityLevel())
                .createdAt(now)
                .updatedAt(now)
                .build());
            sub++;
        }
    }

    private static String composeExtractionBody(String title, String description) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title.trim());
        }
        if (description != null && !description.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(description.trim());
        }
        return sb.toString();
    }

    private static String currentText(String currentText, String editedText, String originalText) {
        if (currentText != null && !currentText.isBlank()) return currentText;
        if (editedText != null && !editedText.isBlank()) return editedText;
        return originalText;
    }

    /**
     * Split by character-window: a chunk per (maxTokens) chars with
     * {@code overlapTokens} characters of left-overlap on each subsequent
     * window. Anything shorter than {@code maxTokens} returns as a
     * single chunk. Returns at least one piece for non-blank input.
     */
    List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int max = strategy.maxTokens();
        int overlap = strategy.overlapTokens();
        if (text.length() <= max) {
            return List.of(text);
        }
        int step = max - overlap;
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + max, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    private static String newChunkId(String prefix) {
        return "chunk_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** New chunk IDs ready for embedding dispatch (M5A C11 consumer). */
    public record ChunkingResult(int staleCount, List<String> newChunkIds) {
        public ChunkingResult {
            if (newChunkIds == null) {
                newChunkIds = List.of();
            }
        }
    }
}
