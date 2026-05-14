package com.meeting.api.client.speaker;

public record CreateSpeakerProfileCommand(
    String tenantId,
    String personId,
    String displayName,
    String consentSource,
    String consentVersion,
    String enrolledBy,
    String requestId,
    String traceId,
    String idempotencyKey
) {
}
