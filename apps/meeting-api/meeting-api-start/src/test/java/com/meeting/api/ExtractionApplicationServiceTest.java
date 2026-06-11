package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.extraction.ExtractionApplicationService;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.client.extraction.ExtractionSummary;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T06:00:00Z");

    @Test
    void extractPersistsAllThreeKindsWithDraftAcceptanceAndEnrichedEvidence() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting());
        InMemoryTranscript transcripts = new InMemoryTranscript(2);
        transcripts.add(segment("seg_01", 0, 1000, "Vendor delay risk."));
        transcripts.add(segment("seg_02", 1000, 2000, "Action: cut PO."));
        InMemoryActionItemRepo actions = new InMemoryActionItemRepo();
        InMemoryDecisionRepo decisions = new InMemoryDecisionRepo();
        InMemoryRiskRepo risks = new InMemoryRiskRepo();
        FakeLlmGateway llm = new FakeLlmGateway();
        llm.next = llmResponse("""
            {
              "actionItems": [
                {"title": "Cut PO", "ownerRawText": "alice", "evidence": [{"segmentId": "seg_02"}]}
              ],
              "decisions": [
                {"title": "Approved budget"}
              ],
              "risks": [
                {"title": "Vendor delay", "severity": "HIGH", "evidence": [{"segmentId": "seg_01"}]}
              ]
            }
            """);

        ExtractionSummary summary = service(meetings, transcripts, actions, decisions, risks, llm)
            .extractForTask("tenant_01", "meeting_01", "task_01");

        assertThat(summary.actionItemsCreated()).isEqualTo(1);
        assertThat(summary.decisionsCreated()).isEqualTo(1);
        assertThat(summary.risksCreated()).isEqualTo(1);

        assertThat(actions.saved).singleElement().satisfies(r -> {
            assertThat(r.acceptanceStatus()).isEqualTo("DRAFT");
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.priority()).isEqualTo("P2");
            assertThat(r.sourceTranscriptVersion()).isEqualTo(2);
            assertThat(r.staleStatus()).isEqualTo(StaleStatus.ACTIVE);
            assertThat(r.evidence()).singleElement().satisfies(ev -> {
                assertThat(ev.segmentId()).isEqualTo("seg_02");
                assertThat(ev.evidenceTextSnapshot()).isEqualTo("Action: cut PO.");
            });
        });
        assertThat(decisions.saved).singleElement().satisfies(r -> {
            assertThat(r.acceptanceStatus()).isEqualTo("DRAFT");
            assertThat(r.status()).isEqualTo("PROPOSED");
        });
        assertThat(risks.saved).singleElement().satisfies(r -> {
            assertThat(r.severity()).isEqualTo("HIGH");
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.acceptanceStatus()).isEqualTo("DRAFT");
        });
    }

    @Test
    void hallucinatedEvidenceSegmentIdIsDropped() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting());
        InMemoryTranscript transcripts = new InMemoryTranscript(1);
        transcripts.add(segment("seg_real", 0, 500, "Real text."));
        InMemoryActionItemRepo actions = new InMemoryActionItemRepo();
        FakeLlmGateway llm = new FakeLlmGateway();
        llm.next = llmResponse("""
            {
              "actionItems": [
                {"title": "Do thing", "evidence": [{"segmentId": "ghost"}, {"segmentId": "seg_real"}]}
              ]
            }
            """);

        service(meetings, transcripts, actions, new InMemoryDecisionRepo(), new InMemoryRiskRepo(), llm)
            .extractForTask("tenant_01", "meeting_01", "task_01");

        assertThat(actions.saved.get(0).evidence()).singleElement()
            .satisfies(ev -> assertThat(ev.segmentId()).isEqualTo("seg_real"));
    }

    @Test
    void missingArraysProduceZeroCounts() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting());
        FakeLlmGateway llm = new FakeLlmGateway();
        llm.next = llmResponse("{}");

        ExtractionSummary summary = service(meetings, new InMemoryTranscript(1),
            new InMemoryActionItemRepo(), new InMemoryDecisionRepo(), new InMemoryRiskRepo(), llm)
            .extractForTask("tenant_01", "meeting_01", "task_01");

        assertThat(summary.actionItemsCreated()).isZero();
        assertThat(summary.decisionsCreated()).isZero();
        assertThat(summary.risksCreated()).isZero();
    }

    private static ExtractionApplicationService service(
        MeetingRepository meetings,
        TranscriptRepository transcripts,
        ActionItemRepository actions,
        DecisionRepository decisions,
        RiskRepository risks,
        LlmGateway llm
    ) {
        return new ExtractionApplicationService(
            meetings,
            transcripts,
            actions,
            decisions,
            risks,
            llm,
            TenantScopedTransaction.immediate(),
            new ObjectMapper(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static LlmGateway.LlmResponse llmResponse(String json) {
        return new LlmGateway.LlmResponse(json, json, 10, 20, 30L, "qwen-plus", "llmlog_t", "art_t");
    }

    private static Meeting meeting() {
        return new Meeting.Builder()
            .id("meeting_01")
            .tenantId("tenant_01")
            .title("M")
            .status(MeetingStatus.PROCESSING)
            .language("zh")
            .securityLevel(SecurityLevel.INTERNAL)
            .transcriptVersion(2)
            .minutesVersion(0)
            .createdAt(NOW.minusMinutes(10))
            .createdBy("user_01")
            .participants(List.of())
            .build();
    }

    private static TranscriptRepository.TranscriptSegmentRecord segment(String id, long startMs, long endMs, String text) {
        return new TranscriptRepository.TranscriptSegmentRecord(
            id, "tenant_01", "meeting_01", 0, startMs, endMs, "SPEAKER_00",
            null, text, null, text,
            BigDecimal.valueOf(0.9), BigDecimal.valueOf(0.9), BigDecimal.ZERO,
            "SEGMENT", 1, null
        );
    }

    private static final class InMemoryMeetingRepo implements MeetingRepository {
        private final Map<String, Meeting> store = new LinkedHashMap<>();

        void add(Meeting m) {
            store.put(m.id(), m);
        }

        @Override
        public Meeting save(Meeting meeting) {
            store.put(meeting.id(), meeting);
            return meeting;
        }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return Optional.ofNullable(store.get(meetingId)).filter(m -> tenantId.equals(m.tenantId()));
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return store.values().stream().filter(m -> tenantId.equals(m.tenantId())).toList();
        }

        @Override
        public void updateStatus(String tenantId, String meetingId, MeetingStatus status) {
        }
    }

    private static final class InMemoryTranscript implements TranscriptRepository {
        private final int currentVersion;
        private final List<TranscriptSegmentRecord> segments = new ArrayList<>();

        private InMemoryTranscript(int currentVersion) {
            this.currentVersion = currentVersion;
        }

        void add(TranscriptSegmentRecord rec) {
            segments.add(rec);
        }

        @Override
        public int currentTranscriptVersion(String tenantId, String meetingId) {
            return currentVersion;
        }

        @Override
        public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion) {
            return new ArrayList<>(segments);
        }

        @Override
        public Optional<TranscriptSegmentRecord> findSegment(String tenantId, String meetingId, String segmentId, int transcriptVersion) {
            return segments.stream().filter(s -> s.segmentId().equals(segmentId)).findFirst();
        }

        @Override
        public void applySegmentEdit(String tenantId, String meetingId, String segmentId, int expectedTranscriptVersion, String editedText, String changedBy, String editReason, OffsetDateTime now) {
        }

        @Override
        public void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments) {
        }

        @Override
        public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion) {
        }
    }

    private static final class InMemoryActionItemRepo implements ActionItemRepository {
        final List<ActionItemRecord> saved = new ArrayList<>();

        @Override
        public String save(ActionItemRecord record) {
            saved.add(record);
            return record.id();
        }

        @Override
        public List<ActionItemRecord> findByMeeting(String tenantId, String meetingId) {
            return saved;
        }

        @Override
        public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {
        }

        @Override
        public void markStaleForMeeting(String tenantId, String meetingId) {
        }
    }

    private static final class InMemoryDecisionRepo implements DecisionRepository {
        final List<DecisionRecord> saved = new ArrayList<>();

        @Override
        public String save(DecisionRecord record) {
            saved.add(record);
            return record.id();
        }

        @Override
        public List<DecisionRecord> findByMeeting(String tenantId, String meetingId) {
            return saved;
        }

        @Override
        public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {
        }

        @Override
        public void markStaleForMeeting(String tenantId, String meetingId) {
        }
    }

    private static final class InMemoryRiskRepo implements RiskRepository {
        final List<RiskRecord> saved = new ArrayList<>();

        @Override
        public String save(RiskRecord record) {
            saved.add(record);
            return record.id();
        }

        @Override
        public List<RiskRecord> findByMeeting(String tenantId, String meetingId) {
            return saved;
        }

        @Override
        public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {
        }

        @Override
        public void markStaleForMeeting(String tenantId, String meetingId) {
        }
    }

    private static final class FakeLlmGateway implements LlmGateway {
        LlmResponse next;
        RuntimeException failWith;

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (failWith != null) throw failWith;
            return next;
        }
    }
}
