package com.meeting.api.client.internal.callback;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record TranscriptCallbackCommand(
    CallbackMetadata metadata,
    String tenantId,
    String meetingId,
    String taskId,
    int attemptNo,
    int transcriptVersion,
    List<Segment> segments,
    Map<String, Object> callbackMetadata,
    String artifactManifestId
) {
    public record Segment(
        String segmentId,
        long startMs,
        long endMs,
        String speakerLabel,
        String text,
        BigDecimal asrConfidence,
        BigDecimal diarizationConfidence,
        BigDecimal speakerConfidence,
        String timestampPrecision
    ) {
    }
}
