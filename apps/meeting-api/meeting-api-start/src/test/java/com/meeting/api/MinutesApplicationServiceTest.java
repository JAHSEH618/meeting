package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.client.minutes.RegenerateMinutesCommand;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.minutes.MinutesRepository;
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

class MinutesApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T05:00:00Z");

    @Test
    void regenerateBuildsMinutesAndEnrichesEvidenceFromCurrentTranscript() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting("meeting_01", SecurityLevel.INTERNAL, 3, 0));

        InMemoryTranscriptRepo transcripts = new InMemoryTranscriptRepo(3);
        transcripts.add(segment("seg_01", 0, 1200, "SPEAKER_00", "Project status discussed."));
        transcripts.add(segment("seg_02", 1200, 3000, "SPEAKER_01", "Risk: vendor delay."));

        InMemoryMinutesRepo minutes = new InMemoryMinutesRepo();
        FakeLlmGateway llm = new FakeLlmGateway();
        llm.next = llmResponse("""
            {
              "title": "Weekly Sync",
              "markdown": "## Summary\\nKey points discussed.",
              "sections": [
                {
                  "type": "SUMMARY",
                  "title": "Summary",
                  "items": [
                    {"text": "Project on track.", "evidence": [{"segmentId": "seg_01"}]},
                    {"text": "Vendor delay noted.", "evidence": [{"segmentId": "seg_02"}]}
                  ]
                }
              ]
            }
            """);
        MinutesApplicationService service = service(meetings, transcripts, minutes, llm);

        var dto = service.regenerate(new RegenerateMinutesCommand(
            "tenant_01", "meeting_01", "user_01", "req_01", "idem_01", 3, 0
        ));

        assertThat(dto.minutesVersion()).isEqualTo(1);
        assertThat(dto.sourceTranscriptVersion()).isEqualTo(3);
        assertThat(dto.staleStatus()).isEqualTo(StaleStatus.ACTIVE);
        assertThat(dto.sections()).hasSize(1);
        var section = dto.sections().get(0);
        assertThat(section.items()).hasSize(2);
        assertThat(section.items().get(0).evidence()).singleElement()
            .satisfies(ev -> {
                assertThat(ev.segmentId()).isEqualTo("seg_01");
                assertThat(ev.startMs()).isEqualTo(0L);
                assertThat(ev.endMs()).isEqualTo(1200L);
                assertThat(ev.evidenceTextSnapshot()).isEqualTo("Project status discussed.");
            });

        assertThat(minutes.saved).hasSize(1);
        assertThat(minutes.meetingVersion).isEqualTo(1);
        assertThat(llm.lastRequest.capability()).isEqualTo("MINUTES_SUMMARY");
        assertThat(llm.lastRequest.taskName()).isEqualTo("MINUTES_SUMMARY");
        assertThat(llm.lastRequest.variables().get("transcript").toString())
            .contains("seg_01")
            .contains("SPEAKER_00")
            .contains("Project status discussed.");
    }

    @Test
    void evidenceWithUnknownSegmentIsDropped() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting("meeting_01", SecurityLevel.PUBLIC, 1, 0));
        InMemoryTranscriptRepo transcripts = new InMemoryTranscriptRepo(1);
        transcripts.add(segment("seg_real", 0, 500, "SPEAKER_00", "Real text."));
        InMemoryMinutesRepo minutes = new InMemoryMinutesRepo();
        FakeLlmGateway llm = new FakeLlmGateway();
        llm.next = llmResponse("""
            {
              "title": "T",
              "markdown": "# m",
              "sections": [{
                "type": "SUMMARY",
                "title": "Summary",
                "items": [{
                  "text": "Item",
                  "evidence": [{"segmentId": "hallucinated_seg"}, {"segmentId": "seg_real"}]
                }]
              }]
            }
            """);
        MinutesApplicationService service = service(meetings, transcripts, minutes, llm);

        var dto = service.regenerate(new RegenerateMinutesCommand(
            "tenant_01", "meeting_01", "user_01", "req_01", "idem_01", null, null
        ));

        assertThat(dto.sections().get(0).items().get(0).evidence()).singleElement()
            .satisfies(ev -> assertThat(ev.segmentId()).isEqualTo("seg_real"));
    }

    @Test
    void versionConflictWhenTranscriptVersionStale() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting("meeting_01", SecurityLevel.INTERNAL, 5, 0));
        MinutesApplicationService service = service(
            meetings,
            new InMemoryTranscriptRepo(5),
            new InMemoryMinutesRepo(),
            new FakeLlmGateway()
        );

        assertThatThrownBy(() -> service.regenerate(new RegenerateMinutesCommand(
            "tenant_01", "meeting_01", "user_01", "req_01", "idem_01", 3, 0
        ))).isInstanceOf(MinutesApplicationService.VersionConflictException.class);
    }

    @Test
    void securityLevelBlockedSurfacesAsException() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting("meeting_01", SecurityLevel.CONFIDENTIAL, 1, 0));
        FakeLlmGateway llm = new FakeLlmGateway();
        llm.failWith = new SecurityLevelBlockedException(SecurityLevel.CONFIDENTIAL, "MINUTES_SUMMARY");
        MinutesApplicationService service = service(
            meetings,
            new InMemoryTranscriptRepo(1),
            new InMemoryMinutesRepo(),
            llm
        );

        assertThatThrownBy(() -> service.regenerate(new RegenerateMinutesCommand(
            "tenant_01", "meeting_01", "user_01", "req_01", "idem_01", null, null
        ))).isInstanceOf(SecurityLevelBlockedException.class);
    }

    @Test
    void malformedLlmJsonRaisesProviderException() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        meetings.add(meeting("meeting_01", SecurityLevel.INTERNAL, 1, 0));
        FakeLlmGateway llm = new FakeLlmGateway();
        llm.next = new LlmGateway.LlmResponse("not json", "not json", 0, 0, 1L, "qwen", "llmlog_x", "art_x");
        MinutesApplicationService service = service(
            meetings,
            new InMemoryTranscriptRepo(1),
            new InMemoryMinutesRepo(),
            llm
        );

        assertThatThrownBy(() -> service.regenerate(new RegenerateMinutesCommand(
            "tenant_01", "meeting_01", "user_01", "req_01", "idem_01", null, null
        ))).isInstanceOf(LlmProviderException.class);
    }

    private static MinutesApplicationService service(
        MeetingRepository meetings,
        TranscriptRepository transcripts,
        MinutesRepository minutes,
        LlmGateway llm
    ) {
        return new MinutesApplicationService(
            meetings,
            minutes,
            transcripts,
            llm,
            TenantScopedTransaction.immediate(),
            new ObjectMapper(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static LlmGateway.LlmResponse llmResponse(String json) {
        return new LlmGateway.LlmResponse(json, json, 10, 20, 30L, "qwen-plus", "llmlog_t", "art_t");
    }

    private static Meeting meeting(String id, SecurityLevel level, int transcriptVersion, int minutesVersion) {
        return new Meeting.Builder()
            .id(id)
            .tenantId("tenant_01")
            .title("M " + id)
            .securityLevel(level)
            .status(MeetingStatus.PROCESSING)
            .language("zh")
            .transcriptVersion(transcriptVersion)
            .minutesVersion(minutesVersion)
            .createdAt(NOW.minusMinutes(10))
            .createdBy("user_01")
            .participants(List.of())
            .build();
    }

    private static TranscriptRepository.TranscriptSegmentRecord segment(
        String id, long startMs, long endMs, String speaker, String text
    ) {
        return new TranscriptRepository.TranscriptSegmentRecord(
            id,
            "tenant_01",
            "meeting_01",
            0,
            startMs,
            endMs,
            speaker,
            null,
            text,
            null,
            text,
            BigDecimal.valueOf(0.95),
            BigDecimal.valueOf(0.95),
            BigDecimal.ZERO,
            "SEGMENT",
            1,
            null
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
            return new ArrayList<>(store.values()).stream()
                .filter(m -> tenantId.equals(m.tenantId()))
                .toList();
        }

        @Override
        public void updateStatus(String tenantId, String meetingId, MeetingStatus status) {
        }
    }

    private static final class InMemoryTranscriptRepo implements TranscriptRepository {
        private final int currentVersion;
        private final List<TranscriptSegmentRecord> segments = new ArrayList<>();

        private InMemoryTranscriptRepo(int currentVersion) {
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

    private static final class InMemoryMinutesRepo implements MinutesRepository {
        private final List<MinutesRecord> saved = new ArrayList<>();
        private int meetingVersion = 0;

        @Override
        public Optional<MinutesRecord> findCurrent(String tenantId, String meetingId) {
            if (saved.isEmpty()) return Optional.empty();
            return Optional.of(saved.get(saved.size() - 1));
        }

        @Override
        public int currentMinutesVersion(String tenantId, String meetingId) {
            return meetingVersion;
        }

        @Override
        public String save(MinutesRecord record) {
            saved.add(record);
            return record.id();
        }

        @Override
        public void incrementMeetingMinutesVersion(String tenantId, String meetingId, int newVersion) {
            meetingVersion = newVersion;
        }

        @Override
        public void markStale(String tenantId, String meetingId) {
        }
    }

    private static final class FakeLlmGateway implements LlmGateway {
        LlmRequest lastRequest;
        LlmResponse next;
        RuntimeException failWith;

        @Override
        public LlmResponse complete(LlmRequest request) {
            this.lastRequest = request;
            if (failWith != null) throw failWith;
            return next;
        }
    }
}
