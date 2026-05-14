package com.meeting.api.domain.transcript;

import java.math.BigDecimal;
import java.util.List;

public interface TranscriptRepository {
    int currentTranscriptVersion(String tenantId, String meetingId);

    List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion);

    void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments);

    void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion);

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
