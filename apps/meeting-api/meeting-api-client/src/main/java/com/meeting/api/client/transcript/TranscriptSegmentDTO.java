package com.meeting.api.client.transcript;

import java.math.BigDecimal;

public record TranscriptSegmentDTO(
    String segmentId,
    long startMs,
    long endMs,
    String speakerLabel,
    String speakerDisplayName,
    String originalText,
    String editedText,
    String currentText,
    BigDecimal asrConfidence,
    BigDecimal diarizationConfidence,
    String timestampPrecision
) {
}
