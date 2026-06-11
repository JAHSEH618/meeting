package com.meeting.api;

import com.meeting.api.app.rag.ChunkingApplicationService;
import com.meeting.api.app.rag.ChunkingApplicationService.ChunkingResult;
import com.meeting.api.app.rag.KnowledgeChunkReindexRequestedEvent;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.StaleStatus;
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
import com.meeting.api.domain.transcript.TranscriptRepository.TranscriptSegmentRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkingApplicationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    // ── rebuildForMeeting ─────────────────────────────────────────

    @Test
    void rebuildForMeetingChunksTranscriptMinutesAndAcceptedExtractionItems() {
        var fx = new Fixtures();
        fx.meetings.put(meeting("mtg_01"));
        fx.transcripts.setVersion("mtg_01", 3);
        fx.transcripts.addSegment(seg("seg_a", "今天讨论一下下季度的产品规划。", 3));
        fx.transcripts.addSegment(seg("seg_b", "我们需要在月底前确定预算。", 3));
        fx.minutes.setCurrent("mtg_01", minutesWithItem("min_01", 2, "讨论了下季度产品规划。"));
        fx.actions.add(actionItem("act_01", "mtg_01", "确定下季度预算", "财务部完成预算编制", "ACCEPTED", 3));
        fx.actions.add(actionItem("act_02", "mtg_01", "撤销的项", "无效", "REJECTED", 3));
        fx.decisions.add(decision("dec_01", "mtg_01", "通过 Q3 营销预算", "全员同意 200 万方案", "ACCEPTED", 3));
        fx.risks.add(risk("risk_01", "mtg_01", "供应链延迟风险", "原材料交付周期不稳定", "PENDING", 3));

        ChunkingResult result = fx.service().rebuildForMeeting("tenant_01", "mtg_01");

        assertThat(result.staleCount()).isEqualTo(0);
        // 2 transcript segs + 1 minutes item + 1 action (REJECTED skipped) + 1 decision + 1 risk = 6
        assertThat(result.newChunkIds()).hasSize(6);
        assertThat(fx.chunks.saved).hasSize(6);

        var bySourceType = groupBySourceType(fx.chunks.saved);
        assertThat(bySourceType.get(KnowledgeSourceType.PRIMARY_TRANSCRIPT)).hasSize(2);
        assertThat(bySourceType.get(KnowledgeSourceType.MINUTES)).hasSize(1);
        assertThat(bySourceType.get(KnowledgeSourceType.ACTION_ITEM)).hasSize(1);
        assertThat(bySourceType.get(KnowledgeSourceType.DECISION)).hasSize(1);
        assertThat(bySourceType.get(KnowledgeSourceType.RISK)).hasSize(1);

        var transcriptChunk = bySourceType.get(KnowledgeSourceType.PRIMARY_TRANSCRIPT).get(0);
        assertThat(transcriptChunk.tenantId()).isEqualTo("tenant_01");
        assertThat(transcriptChunk.meetingId()).isEqualTo("mtg_01");
        assertThat(transcriptChunk.documentId()).isNull();
        assertThat(transcriptChunk.sourceSegmentId()).isEqualTo("seg_a");
        assertThat(transcriptChunk.sourceId()).isEqualTo("seg_a#0");
        assertThat(transcriptChunk.transcriptVersion()).isEqualTo(3);
        assertThat(transcriptChunk.chunkStrategyVersion()).isEqualTo("default-zh-v1");
        assertThat(transcriptChunk.securityLevel()).isEqualTo(SecurityLevel.INTERNAL);
        assertThat(transcriptChunk.createdAt()).isEqualTo(NOW);
        assertThat(transcriptChunk.contentHash()).hasSize(64);

        var summaryChunk = bySourceType.get(KnowledgeSourceType.MINUTES).get(0);
        assertThat(summaryChunk.minutesVersion()).isEqualTo(2);
        assertThat(summaryChunk.transcriptVersion()).isEqualTo(3);
        assertThat(summaryChunk.sourceId()).startsWith("min_01:sec_0:itm_0");

        var actionChunk = bySourceType.get(KnowledgeSourceType.ACTION_ITEM).get(0);
        assertThat(actionChunk.content())
            .contains("确定下季度预算")
            .contains("财务部完成预算编制");
        assertThat(actionChunk.transcriptVersion()).isEqualTo(3);
        assertThat(actionChunk.minutesVersion()).isEqualTo(2);
    }

    @Test
    void rebuildForMeetingSkipsBlankExtractionItems() {
        var fx = new Fixtures();
        fx.meetings.put(meeting("mtg_blank"));
        fx.transcripts.setVersion("mtg_blank", 0);  // no transcript yet
        fx.actions.add(actionItem("act_b", "mtg_blank", "  ", "   ", "ACCEPTED", null));
        fx.decisions.add(decision("dec_b", "mtg_blank", null, null, "ACCEPTED", null));

        ChunkingResult result = fx.service().rebuildForMeeting("tenant_01", "mtg_blank");

        assertThat(result.newChunkIds()).isEmpty();
        assertThat(fx.chunks.saved).isEmpty();
    }

    @Test
    void rebuildForMeetingHonoursStaleCount() {
        var fx = new Fixtures();
        fx.meetings.put(meeting("mtg_stale"));
        fx.chunks.staleReturn = 4;

        ChunkingResult result = fx.service().rebuildForMeeting("tenant_01", "mtg_stale");

        assertThat(result.staleCount()).isEqualTo(4);
        assertThat(fx.chunks.meetingStaleCalls).containsExactly("mtg_stale");
    }

    @Test
    void rebuildForMeetingThrowsWhenMeetingMissing() {
        var fx = new Fixtures();

        assertThatThrownBy(() -> fx.service().rebuildForMeeting("tenant_01", "ghost"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("meeting not found")
            .hasMessageContaining("ghost");
    }

    @Test
    void rebuildForMeetingSplitsTranscriptLongerThanWindow() {
        var fx = new Fixtures();
        fx.meetings.put(meeting("mtg_long"));
        fx.transcripts.setVersion("mtg_long", 1);
        // window=8, overlap=2 → step=6. 20-char body → pieces at 0..8, 6..14, 12..20.
        String body = repeat("我", 20);
        fx.transcripts.addSegment(seg("seg_big", body, 1));

        ChunkingApplicationService svc = new ChunkingApplicationService(
            fx.transcripts, fx.minutes, fx.actions, fx.decisions, fx.risks,
            fx.documents, fx.documentChunks, fx.meetings, fx.chunks,
            new ChunkStrategy("test-zh", 64, 16, "chinese-char"),
            fx.events, CLOCK
        );
        // shrink strategy explicitly for the test rather than relying on prod defaults
        ChunkingApplicationService scoped = new ChunkingApplicationService(
            fx.transcripts, fx.minutes, fx.actions, fx.decisions, fx.risks,
            fx.documents, fx.documentChunks, fx.meetings, fx.chunks,
            new ChunkStrategy("tiny", 32, 8, "tok"),
            fx.events, CLOCK
        );

        ChunkingResult result = scoped.rebuildForMeeting("tenant_01", "mtg_long");

        // single 20-char body fits inside maxTokens=32, so still one chunk
        assertThat(result.newChunkIds()).hasSize(1);
        var stored = fx.chunks.saved.get(0);
        assertThat(stored.content()).isEqualTo(body);

        // Now grow the body past the window and re-run
        fx.chunks.clear();
        fx.transcripts.clear();
        fx.transcripts.setVersion("mtg_long", 1);
        String wide = repeat("话", 80);
        fx.transcripts.addSegment(seg("seg_wide", wide, 1));

        scoped.rebuildForMeeting("tenant_01", "mtg_long");

        // 80 chars / step=24 (max=32, overlap=8) → windows starting at 0, 24, 48
        // window at 48 ends at 80, so loop breaks. → 3 pieces.
        assertThat(fx.chunks.saved).hasSize(3);
        assertThat(fx.chunks.saved.get(0).content()).hasSize(32);
        assertThat(fx.chunks.saved.get(1).content()).hasSize(32);
        assertThat(fx.chunks.saved.get(2).content()).hasSize(32);
        // overlap proves sliding window
        assertThat(fx.chunks.saved.get(0).content().substring(24))
            .isEqualTo(fx.chunks.saved.get(1).content().substring(0, 8));
    }

    // ── rebuildForDocument ────────────────────────────────────────

    @Test
    void rebuildForDocumentChunksAllDocumentChunks() {
        var fx = new Fixtures();
        fx.documents.put(document("doc_01"));
        fx.documentChunks.add(new DocumentChunkRepository.ChunkRecord(
            "src_1", "tenant_01", "doc_01", 0, 1, "文档第一段。", "hash1"));
        fx.documentChunks.add(new DocumentChunkRepository.ChunkRecord(
            "src_2", "tenant_01", "doc_01", 1, 1, "文档第二段。", "hash2"));
        fx.documentChunks.add(new DocumentChunkRepository.ChunkRecord(
            "src_3", "tenant_01", "doc_01", 2, 2, "   ", "hash3"));  // blank skipped

        ChunkingResult result = fx.service().rebuildForDocument("tenant_01", "doc_01");

        assertThat(result.newChunkIds()).hasSize(2);
        assertThat(fx.chunks.documentStaleCalls).containsExactly("doc_01");
        var bySource = groupBySourceType(fx.chunks.saved);
        assertThat(bySource.get(KnowledgeSourceType.DOCUMENT)).hasSize(2);

        var first = bySource.get(KnowledgeSourceType.DOCUMENT).get(0);
        assertThat(first.documentId()).isEqualTo("doc_01");
        assertThat(first.meetingId()).isNull();
        assertThat(first.sourceId()).isEqualTo("src_1#0");
        assertThat(first.securityLevel()).isEqualTo(SecurityLevel.CONFIDENTIAL);
        assertThat(first.transcriptVersion()).isNull();
        assertThat(first.minutesVersion()).isNull();
    }

    @Test
    void rebuildForDocumentThrowsWhenDocumentMissing() {
        var fx = new Fixtures();

        assertThatThrownBy(() -> fx.service().rebuildForDocument("tenant_01", "ghost-doc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("document not found");
    }

    @Test
    void chunkingResultDefaultsNullIdsToEmptyList() {
        ChunkingResult r = new ChunkingResult(3, null);
        assertThat(r.newChunkIds()).isEmpty();
        assertThat(r.staleCount()).isEqualTo(3);
    }

    @Test
    void rebuildForMeetingPublishesReindexEventWithChunkIds() {
        var fx = new Fixtures();
        fx.meetings.put(meeting("mtg_evt"));
        fx.transcripts.setVersion("mtg_evt", 2);
        fx.transcripts.addSegment(seg("seg_e1", "事件触发测试。", 2));
        fx.minutes.setCurrent("mtg_evt", minutesWithItem("min_evt", 1, "纪要内容"));

        ChunkingResult result = fx.service().rebuildForMeeting("tenant_01", "mtg_evt");

        var events = fx.events.reindexEvents();
        assertThat(events).hasSize(1);
        var evt = events.get(0);
        assertThat(evt.tenantId()).isEqualTo("tenant_01");
        assertThat(evt.meetingId()).isEqualTo("mtg_evt");
        assertThat(evt.documentId()).isNull();
        assertThat(evt.chunkIds()).containsExactlyElementsOf(result.newChunkIds());
        assertThat(evt.securityLevel()).isEqualTo(SecurityLevel.INTERNAL);
        assertThat(evt.chunkStrategyVersion()).isEqualTo("default-zh-v1");
        assertThat(evt.transcriptVersion()).isEqualTo(2);
        assertThat(evt.minutesVersion()).isEqualTo(1);
    }

    @Test
    void rebuildForDocumentPublishesReindexEventScopedToDocument() {
        var fx = new Fixtures();
        fx.documents.put(document("doc_evt"));
        fx.documentChunks.add(new DocumentChunkRepository.ChunkRecord(
            "src_e1", "tenant_01", "doc_evt", 0, 1, "文档内容。", "h"));

        ChunkingResult result = fx.service().rebuildForDocument("tenant_01", "doc_evt");

        var events = fx.events.reindexEvents();
        assertThat(events).hasSize(1);
        var evt = events.get(0);
        assertThat(evt.meetingId()).isNull();
        assertThat(evt.documentId()).isEqualTo("doc_evt");
        assertThat(evt.chunkIds()).containsExactlyElementsOf(result.newChunkIds());
        assertThat(evt.securityLevel()).isEqualTo(SecurityLevel.CONFIDENTIAL);
        assertThat(evt.transcriptVersion()).isNull();
        assertThat(evt.minutesVersion()).isNull();
    }

    @Test
    void rebuildEmitsNoEventWhenNoChunksProduced() {
        var fx = new Fixtures();
        fx.meetings.put(meeting("mtg_empty"));
        // no transcript, no minutes, no items → nothing to chunk

        fx.service().rebuildForMeeting("tenant_01", "mtg_empty");

        assertThat(fx.events.reindexEvents()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static Map<KnowledgeSourceType, List<KnowledgeChunk>> groupBySourceType(List<KnowledgeChunk> chunks) {
        Map<KnowledgeSourceType, List<KnowledgeChunk>> out = new LinkedHashMap<>();
        for (var c : chunks) {
            out.computeIfAbsent(c.sourceType(), k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    private static String repeat(String unit, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(unit);
        return sb.toString();
    }

    private static Meeting meeting(String id) {
        return new Meeting.Builder()
            .id(id).tenantId("tenant_01").title("Test " + id)
            .status(MeetingStatus.CREATED)
            .language("zh").transcriptVersion(0).minutesVersion(0)
            .createdAt(NOW).createdBy("user_01").build();
    }

    private static TranscriptSegmentRecord seg(String segmentId, String text, int version) {
        return new TranscriptSegmentRecord(
            segmentId, "tenant_01", "mtg_long".equals(segmentId) ? "mtg_long" : "mtg_01",
            0, 0L, 1000L, "spk_A", "Alice",
            text, null, text,
            new BigDecimal("0.95"), new BigDecimal("0.90"), new BigDecimal("0.85"),
            "MS", version, "manifest_01"
        );
    }

    private static MinutesRepository.MinutesRecord minutesWithItem(String id, int version, String itemText) {
        var item = new MinutesRepository.ItemRecord(itemText, List.of());
        var section = new MinutesRepository.SectionRecord("DISCUSSION", "讨论", List.of(item));
        return new MinutesRepository.MinutesRecord(
            id, "tenant_01", "mtg_01", version, 3, "Title",
            "# md", List.of(section),
            "READY", StaleStatus.ACTIVE, "manifest_min", "user_01", NOW, NOW
        );
    }

    private static ActionItemRepository.ActionItemRecord actionItem(
        String id, String meetingId, String title, String description, String acceptanceStatus, Integer srcVersion
    ) {
        return new ActionItemRepository.ActionItemRecord(
            id, "tenant_01", meetingId, "AI", title, description,
            null, null, null, null, "MEDIUM", "OPEN", acceptanceStatus,
            srcVersion, StaleStatus.ACTIVE, List.of(), "manifest_act", NOW, NOW
        );
    }

    private static DecisionRepository.DecisionRecord decision(
        String id, String meetingId, String title, String description, String acceptanceStatus, Integer srcVersion
    ) {
        return new DecisionRepository.DecisionRecord(
            id, "tenant_01", meetingId, title, description, "FINAL",
            acceptanceStatus, srcVersion, StaleStatus.ACTIVE, List.of(),
            "manifest_dec", NOW, NOW
        );
    }

    private static RiskRepository.RiskRecord risk(
        String id, String meetingId, String title, String description, String acceptanceStatus, Integer srcVersion
    ) {
        return new RiskRepository.RiskRecord(
            id, "tenant_01", meetingId, title, description, "HIGH", "OPEN",
            acceptanceStatus, srcVersion, StaleStatus.ACTIVE, List.of(),
            "manifest_risk", NOW, NOW
        );
    }

    private static DocumentRepository.DocumentRecord document(String id) {
        return new DocumentRepository.DocumentRecord(
            id, "tenant_01", null, "Doc " + id, "file_" + id, "PDF", "UPLOADED",
            SecurityLevel.CONFIDENTIAL, "EXTRACTED", null, "sha256:" + id,
            "user_01", NOW, NOW, null
        );
    }

    // ── In-memory fakes ───────────────────────────────────────────

    private static final class Fixtures {
        final InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        final InMemoryTranscriptRepo transcripts = new InMemoryTranscriptRepo();
        final InMemoryMinutesRepo minutes = new InMemoryMinutesRepo();
        final InMemoryActionItemRepo actions = new InMemoryActionItemRepo();
        final InMemoryDecisionRepo decisions = new InMemoryDecisionRepo();
        final InMemoryRiskRepo risks = new InMemoryRiskRepo();
        final InMemoryDocumentRepo documents = new InMemoryDocumentRepo();
        final InMemoryDocumentChunkRepo documentChunks = new InMemoryDocumentChunkRepo();
        final CapturingKnowledgeChunkRepo chunks = new CapturingKnowledgeChunkRepo();
        final CapturingEventPublisher events = new CapturingEventPublisher();

        ChunkingApplicationService service() {
            return new ChunkingApplicationService(
                transcripts, minutes, actions, decisions, risks,
                documents, documentChunks, meetings, chunks, events, CLOCK
            );
        }
    }

    private static final class CapturingEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        List<KnowledgeChunkReindexRequestedEvent> reindexEvents() {
            return events.stream()
                .filter(KnowledgeChunkReindexRequestedEvent.class::isInstance)
                .map(KnowledgeChunkReindexRequestedEvent.class::cast)
                .toList();
        }
    }

    private static final class InMemoryMeetingRepo implements MeetingRepository {
        final Map<String, Meeting> store = new LinkedHashMap<>();

        void put(Meeting m) { store.put(m.id(), m); }

        @Override public Meeting save(Meeting meeting) { store.put(meeting.id(), meeting); return meeting; }
        @Override public Optional<Meeting> findById(String tenantId, String meetingId) {
            return Optional.ofNullable(store.get(meetingId)).filter(m -> tenantId.equals(m.tenantId()));
        }
        @Override public List<Meeting> findByTenantId(String tenantId) {
            return store.values().stream().filter(m -> tenantId.equals(m.tenantId())).toList();
        }
    }

    private static final class InMemoryTranscriptRepo implements TranscriptRepository {
        final Map<String, Integer> versions = new LinkedHashMap<>();
        final List<TranscriptSegmentRecord> segments = new ArrayList<>();

        void setVersion(String meetingId, int v) { versions.put(meetingId, v); }
        void addSegment(TranscriptSegmentRecord s) { segments.add(s); }
        void clear() { segments.clear(); }

        @Override public int currentTranscriptVersion(String tenantId, String meetingId) {
            return versions.getOrDefault(meetingId, 0);
        }
        @Override public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int v) {
            return segments.stream()
                .filter(s -> s.transcriptVersion() == v)
                .toList();
        }
        @Override public Optional<TranscriptSegmentRecord> findSegment(String tenantId, String meetingId, String segmentId, int v) {
            return segments.stream().filter(s -> s.segmentId().equals(segmentId)).findFirst();
        }
        @Override public void replaceTranscript(String tenantId, String meetingId, int v, String manifestId, List<TranscriptSegmentRecord> segs) {}
        @Override public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int v) {}
        @Override public void applySegmentEdit(String tenantId, String meetingId, String segmentId, int v, String editedText, String changedBy, String reason, OffsetDateTime now) {}
    }

    private static final class InMemoryMinutesRepo implements MinutesRepository {
        final Map<String, MinutesRecord> store = new LinkedHashMap<>();

        void setCurrent(String meetingId, MinutesRecord rec) { store.put(meetingId, rec); }

        @Override public Optional<MinutesRecord> findCurrent(String tenantId, String meetingId) {
            return Optional.ofNullable(store.get(meetingId));
        }
        @Override public int currentMinutesVersion(String tenantId, String meetingId) {
            return store.containsKey(meetingId) ? store.get(meetingId).minutesVersion() : 0;
        }
        @Override public String save(MinutesRecord record) { store.put(record.meetingId(), record); return record.id(); }
        @Override public void incrementMeetingMinutesVersion(String tenantId, String meetingId, int v) {}
        @Override public void markStale(String tenantId, String meetingId) {}
    }

    private static final class InMemoryActionItemRepo implements ActionItemRepository {
        final List<ActionItemRecord> store = new ArrayList<>();
        void add(ActionItemRecord r) { store.add(r); }
        @Override public String save(ActionItemRecord record) { store.add(record); return record.id(); }
        @Override public List<ActionItemRecord> findByMeeting(String tenantId, String meetingId) {
            return store.stream().filter(r -> meetingId.equals(r.meetingId())).toList();
        }
        @Override public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {}
        @Override public void markStaleForMeeting(String tenantId, String meetingId) {}
    }

    private static final class InMemoryDecisionRepo implements DecisionRepository {
        final List<DecisionRecord> store = new ArrayList<>();
        void add(DecisionRecord r) { store.add(r); }
        @Override public String save(DecisionRecord record) { store.add(record); return record.id(); }
        @Override public List<DecisionRecord> findByMeeting(String tenantId, String meetingId) {
            return store.stream().filter(r -> meetingId.equals(r.meetingId())).toList();
        }
        @Override public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {}
        @Override public void markStaleForMeeting(String tenantId, String meetingId) {}
    }

    private static final class InMemoryRiskRepo implements RiskRepository {
        final List<RiskRecord> store = new ArrayList<>();
        void add(RiskRecord r) { store.add(r); }
        @Override public String save(RiskRecord record) { store.add(record); return record.id(); }
        @Override public List<RiskRecord> findByMeeting(String tenantId, String meetingId) {
            return store.stream().filter(r -> meetingId.equals(r.meetingId())).toList();
        }
        @Override public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {}
        @Override public void markStaleForMeeting(String tenantId, String meetingId) {}
    }

    private static final class InMemoryDocumentRepo implements DocumentRepository {
        final Map<String, DocumentRecord> store = new LinkedHashMap<>();
        void put(DocumentRecord r) { store.put(r.id(), r); }
        @Override public String save(DocumentRecord record) { store.put(record.id(), record); return record.id(); }
        @Override public Optional<DocumentRecord> findById(String tenantId, String documentId) {
            return Optional.ofNullable(store.get(documentId)).filter(d -> tenantId.equals(d.tenantId()));
        }
        @Override public List<DocumentRecord> listByTenant(String tenantId, boolean includeDeleted) {
            return store.values().stream().filter(d -> tenantId.equals(d.tenantId())).toList();
        }
        @Override public void updateExtractionStatus(String tenantId, String documentId, String extractionStatus, String status, OffsetDateTime now) {}
        @Override public void softDelete(String tenantId, String documentId, OffsetDateTime now) {}
    }

    private static final class InMemoryDocumentChunkRepo implements DocumentChunkRepository {
        final List<ChunkRecord> store = new ArrayList<>();
        void add(ChunkRecord r) { store.add(r); }
        @Override public void replaceChunks(String tenantId, String documentId, List<ChunkRecord> chunks, OffsetDateTime now) {
            store.clear();
            store.addAll(chunks);
        }
        @Override public List<ChunkRecord> findByDocument(String tenantId, String documentId) {
            return store.stream().filter(c -> documentId.equals(c.documentId())).toList();
        }
    }

    private static final class CapturingKnowledgeChunkRepo implements KnowledgeChunkRepository {
        final List<KnowledgeChunk> saved = new ArrayList<>();
        final List<String> meetingStaleCalls = new ArrayList<>();
        final List<String> documentStaleCalls = new ArrayList<>();
        int staleReturn = 0;

        void clear() { saved.clear(); meetingStaleCalls.clear(); documentStaleCalls.clear(); }

        @Override public void saveAll(Collection<KnowledgeChunk> chunks) { saved.addAll(chunks); }
        @Override public int markStaleForMeeting(String tenantId, String meetingId) {
            meetingStaleCalls.add(meetingId);
            return staleReturn;
        }
        @Override public int markStaleForDocument(String tenantId, String documentId) {
            documentStaleCalls.add(documentId);
            return staleReturn;
        }
    }
}
