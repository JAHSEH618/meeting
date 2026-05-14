package com.meeting.api;

import com.meeting.api.app.transcript.TranscriptApplicationService;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static com.meeting.api.client.enums.SecurityLevel.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;

class TranscriptApplicationServiceTest {

    @Test
    void getReturnsCurrentTranscriptVersionSegmentsInOrder() {
        TranscriptApplicationService service = new TranscriptApplicationService(
            new OneMeetingRepository(),
            new StubTranscriptRepository()
        );

        var transcript = service.get("tenant_01", "meeting_01").orElseThrow();

        assertThat(transcript.transcriptVersion()).isEqualTo(2);
        assertThat(transcript.staleStatus()).isEqualTo("ACTIVE");
        assertThat(transcript.segments()).extracting("segmentId").containsExactly("seg_01", "seg_02");
        assertThat(transcript.segments().get(0).currentText()).isEqualTo("edited hello");
    }

    private static final class OneMeetingRepository implements MeetingRepository {
        private final Meeting meeting = new Meeting.Builder()
            .id("meeting_01")
            .tenantId("tenant_01")
            .title("Weekly")
            .securityLevel(INTERNAL)
            .status(MeetingStatus.PROCESSING)
            .language("zh")
            .transcriptVersion(2)
            .minutesVersion(0)
            .createdAt(OffsetDateTime.parse("2026-05-14T02:00:00Z"))
            .createdBy("user_01")
            .participants(List.of())
            .build();

        @Override
        public Meeting save(Meeting meeting) {
            return meeting;
        }

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
        @Override
        public int currentTranscriptVersion(String tenantId, String meetingId) {
            return 2;
        }

        @Override
        public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion) {
            return List.of(
                segment("seg_02", 1, "world", null),
                segment("seg_01", 0, "hello", "edited hello")
            );
        }

        @Override
        public void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments) {
        }

        @Override
        public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion) {
        }

        private static TranscriptSegmentRecord segment(String id, int index, String originalText, String editedText) {
            return new TranscriptSegmentRecord(
                id,
                "tenant_01",
                "meeting_01",
                index,
                index * 1000L,
                index * 1000L + 800,
                "SPEAKER_00",
                null,
                originalText,
                editedText,
                editedText == null ? originalText : editedText,
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(0.8),
                BigDecimal.ZERO,
                "SEGMENT",
                2,
                null
            );
        }
    }
}
