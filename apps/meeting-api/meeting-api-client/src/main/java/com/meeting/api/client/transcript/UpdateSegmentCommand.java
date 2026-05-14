package com.meeting.api.client.transcript;

public record UpdateSegmentCommand(
    String tenantId,
    String meetingId,
    String segmentId,
    String editedText,
    String editReason,
    int expectedTranscriptVersion,
    String requestedBy,
    String requestId,
    String traceId,
    String idempotencyKey
) {
}
