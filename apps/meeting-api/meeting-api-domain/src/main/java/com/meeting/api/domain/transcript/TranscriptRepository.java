package com.meeting.api.domain.transcript;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TranscriptRepository {
    int currentTranscriptVersion(String tenantId, String meetingId);

    List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion);

    Optional<TranscriptSegmentRecord> findSegment(String tenantId, String meetingId, String segmentId, int transcriptVersion);

    void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments);

    void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion);

    /**
     * Apply a user edit to a single segment.
     * <p>
     * Preserves {@code original_text}; sets {@code edited_text} and {@code text} (current view);
     * records a {@code transcript_change_events} audit row in the same SQL transaction.
     * Callers must confirm {@code expectedTranscriptVersion} matches before invoking; this method
     * additionally guards against latest-wins races by including version in the WHERE clause.
     */
    void applySegmentEdit(
        String tenantId,
        String meetingId,
        String segmentId,
        int expectedTranscriptVersion,
        String editedText,
        String changedBy,
        String editReason,
        OffsetDateTime now
    );

    record TranscriptSegmentRecord(
        String segmentId,
        String tenantId,
        String meetingId,
        int segmentIndex,
        long startMs,
        long endMs,
        String speakerLabel,
        String speakerDisplayName,
        String originalText,
        String editedText,
        String currentText,
        BigDecimal asrConfidence,
        BigDecimal diarizationConfidence,
        BigDecimal speakerConfidence,
        String timestampPrecision,
        int transcriptVersion,
        String artifactManifestId
    ) {
    }
}
