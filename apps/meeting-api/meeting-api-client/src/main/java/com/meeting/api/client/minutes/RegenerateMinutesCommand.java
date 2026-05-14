package com.meeting.api.client.minutes;

public record RegenerateMinutesCommand(
    String tenantId,
    String meetingId,
    String requestedBy,
    String requestId,
    String idempotencyKey,
    Integer expectedTranscriptVersion,
    Integer expectedMinutesVersion
) {
}
