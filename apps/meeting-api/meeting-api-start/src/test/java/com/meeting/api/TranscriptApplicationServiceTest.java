package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.transcript.TranscriptApplicationService;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.client.transcript.UpdateSegmentCommand;
import com.meeting.api.client.transcript.UpdateSegmentResult;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.minutes.MinutesRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T07:00:00Z");

    @Test
    void getReturnsCurrentTranscriptVersionSegmentsInOrder() {
        StubTranscriptRepository transcript = new StubTranscriptRepository(2);
        transcript.add(segment("seg_02", 1, "world", null));
        transcript.add(segment("seg_01", 0, "hello", "edited hello"));
        TranscriptApplicationService service = service(transcript);

        var dto = service.get("tenant_01", "meeting_01").orElseThrow();

        assertThat(dto.transcriptVersion()).isEqualTo(2);
        assertThat(dto.staleStatus()).isEqualTo("ACTIVE");
        assertThat(dto.segments()).extracting("segmentId").containsExactly("seg_01", "seg_02");
        assertThat(dto.segments().get(0).currentText()).isEqualTo("edited hello");
    }

    @Test
    void updateSegmentAppliesEditAndMarksDownstreamStale() {
        StubTranscriptRepository transcript = new StubTranscriptRepository(2);
        transcript.add(segment("seg_01", 0, "hello", null));
        CountingMinutesRepo minutes = new CountingMinutesRepo();
        CountingActionRepo actions = new CountingActionRepo();
        CountingDecisionRepo decisions = new CountingDecisionRepo();
        CountingRiskRepo risks = new CountingRiskRepo();
        CountingChunkRepo chunks = new CountingChunkRepo();
        TranscriptApplicationService service = service(transcript, minutes, actions, decisions, risks, chunks);

        UpdateSegmentResult result = service.updateSegment(new UpdateSegmentCommand(
            "tenant_01", "meeting_01", "seg_01",
            "edited hello",
            "user fix",
            2,
            "user_01", "req_01", "trace_01", "idem_01"
        ));

        assertThat(result.transcriptVersion()).isEqualTo(2);
        assertThat(result.editStatus()).isEqualTo("EDITED");
        assertThat(result.downstreamStaleMarked()).isTrue();
        assertThat(transcript.edits).singleElement().satisfies(edit -> {
            assertThat(edit.segmentId).isEqualTo("seg_01");
            assertThat(edit.editedText).isEqualTo("edited hello");
            assertThat(edit.expectedTranscriptVersion).isEqualTo(2);
            assertThat(edit.editReason).isEqualTo("user fix");
        });
        assertThat(minutes.staleCalls).isEqualTo(1);
        assertThat(actions.staleCalls).isEqualTo(1);
        assertThat(decisions.staleCalls).isEqualTo(1);
        assertThat(risks.staleCalls).isEqualTo(1);
        assertThat(chunks.staleCalls).isEqualTo(1);
    }

    @Test
    void updateSegmentRejectsVersionConflict() {
        StubTranscriptRepository transcript = new StubTranscriptRepository(3);
        transcript.add(segment("seg_01", 0, "hello", null));
        TranscriptApplicationService service = service(transcript);

        assertThatThrownBy(() -> service.updateSegment(new UpdateSegmentCommand(
            "tenant_01", "meeting_01", "seg_01",
            "edited hello", "user fix", 2,
            "user_01", "req_01", "trace_01", "idem_01"
        ))).isInstanceOf(TranscriptApplicationService.TranscriptVersionConflictException.class);

        assertThat(transcript.edits).isEmpty();
    }

    @Test
    void updateSegmentRejectsNullEditedText() {
        StubTranscriptRepository transcript = new StubTranscriptRepository(1);
        transcript.add(segment("seg_01", 0, "hello", null));
        TranscriptApplicationService service = service(transcript);

        assertThatThrownBy(() -> service.updateSegment(new UpdateSegmentCommand(
            "tenant_01", "meeting_01", "seg_01",
            null, "user fix", 1,
            "user_01", "req_01", "trace_01", "idem_01"
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    private static TranscriptApplicationService service(StubTranscriptRepository transcript) {
        return service(transcript, new CountingMinutesRepo(), new CountingActionRepo(),
            new CountingDecisionRepo(), new CountingRiskRepo(), new CountingChunkRepo());
    }

    private static TranscriptApplicationService service(
        StubTranscriptRepository transcript,
        MinutesRepository minutes,
        ActionItemRepository actions,
        DecisionRepository decisions,
        RiskRepository risks,
        KnowledgeChunkRepository chunks
    ) {
        return new TranscriptApplicationService(
            new OneMeetingRepository(transcript.currentVersion),
            transcript,
            minutes,
            actions,
            decisions,
            risks,
            chunks,
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static TranscriptRepository.TranscriptSegmentRecord segment(String id, int index, String original, String edited) {
        return new TranscriptRepository.TranscriptSegmentRecord(
            id, "tenant_01", "meeting_01", index,
            index * 1000L, index * 1000L + 800,
            "SPEAKER_00", null,
            original, edited,
            edited == null ? original : edited,
            BigDecimal.valueOf(0.9), BigDecimal.valueOf(0.8), BigDecimal.ZERO,
            "SEGMENT", 2, null
        );
    }

    private static final class OneMeetingRepository implements MeetingRepository {
        private final Meeting meeting;

        private OneMeetingRepository(int transcriptVersion) {
            this.meeting = new Meeting.Builder()
                .id("meeting_01")
                .tenantId("tenant_01")
                .title("Weekly")
                .securityLevel(INTERNAL)
                .status(MeetingStatus.PROCESSING)
                .language("zh")
                .transcriptVersion(transcriptVersion)
                .minutesVersion(0)
                .createdAt(OffsetDateTime.parse("2026-05-14T02:00:00Z"))
                .createdBy("user_01")
                .participants(List.of())
                .build();
        }

        @Override
        public Meeting save(Meeting meeting) { return meeting; }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return tenantId.equals(meeting.tenantId()) && meetingId.equals(meeting.id()) ? Optional.of(meeting) : Optional.empty();
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return List.of(meeting);
        }
    }

    private static final class StubTranscriptRepository implements TranscriptRepository {
        private final int currentVersion;
        private final Map<String, TranscriptSegmentRecord> segments = new HashMap<>();
        private final List<EditOp> edits = new ArrayList<>();

        private StubTranscriptRepository(int currentVersion) {
            this.currentVersion = currentVersion;
        }

        void add(TranscriptSegmentRecord rec) {
            segments.put(rec.segmentId(), rec);
        }

        @Override
        public int currentTranscriptVersion(String tenantId, String meetingId) {
            return currentVersion;
        }

        @Override
        public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion) {
            return new ArrayList<>(segments.values());
        }

        @Override
        public Optional<TranscriptSegmentRecord> findSegment(String tenantId, String meetingId, String segmentId, int transcriptVersion) {
            return Optional.ofNullable(segments.get(segmentId));
        }

        @Override
        public void applySegmentEdit(String tenantId, String meetingId, String segmentId, int expectedTranscriptVersion, String editedText, String changedBy, String editReason, OffsetDateTime now) {
            edits.add(new EditOp(segmentId, editedText, expectedTranscriptVersion, editReason));
        }

        @Override
        public void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments) {
        }

        @Override
        public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion) {
        }

        private static final class EditOp {
            final String segmentId;
            final String editedText;
            final int expectedTranscriptVersion;
            final String editReason;

            EditOp(String segmentId, String editedText, int expectedTranscriptVersion, String editReason) {
                this.segmentId = segmentId;
                this.editedText = editedText;
                this.expectedTranscriptVersion = expectedTranscriptVersion;
                this.editReason = editReason;
            }
        }
    }

    private static final class CountingMinutesRepo implements MinutesRepository {
        int staleCalls;
        @Override public Optional<MinutesRecord> findCurrent(String t, String m) { return Optional.empty(); }
        @Override public int currentMinutesVersion(String t, String m) { return 0; }
        @Override public String save(MinutesRecord r) { return r.id(); }
        @Override public void incrementMeetingMinutesVersion(String t, String m, int v) {}
        @Override public void markStale(String t, String m) { staleCalls++; }
    }

    private static final class CountingActionRepo implements ActionItemRepository {
        int staleCalls;
        @Override public String save(ActionItemRecord r) { return r.id(); }
        @Override public List<ActionItemRecord> findByMeeting(String t, String m) { return List.of(); }
        @Override public void markAcceptance(String t, String id, String s, String u, OffsetDateTime n) {}
        @Override public void markStaleForMeeting(String t, String m) { staleCalls++; }
    }

    private static final class CountingDecisionRepo implements DecisionRepository {
        int staleCalls;
        @Override public String save(DecisionRecord r) { return r.id(); }
        @Override public List<DecisionRecord> findByMeeting(String t, String m) { return List.of(); }
        @Override public void markAcceptance(String t, String id, String s, String u, OffsetDateTime n) {}
        @Override public void markStaleForMeeting(String t, String m) { staleCalls++; }
    }

    private static final class CountingRiskRepo implements RiskRepository {
        int staleCalls;
        @Override public String save(RiskRecord r) { return r.id(); }
        @Override public List<RiskRecord> findByMeeting(String t, String m) { return List.of(); }
        @Override public void markAcceptance(String t, String id, String s, String u, OffsetDateTime n) {}
        @Override public void markStaleForMeeting(String t, String m) { staleCalls++; }
    }

    private static final class CountingChunkRepo implements KnowledgeChunkRepository {
        int staleCalls;
        @Override public int markStaleForMeeting(String t, String m) { staleCalls++; return 1; }
    }

    // unused enum guard suppresses warnings about StaleStatus import not being needed
    @SuppressWarnings("unused")
    private static final StaleStatus UNUSED_BUT_KEEPS_IMPORT_CONSISTENT = StaleStatus.ACTIVE;
}
