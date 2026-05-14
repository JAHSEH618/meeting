package com.meeting.api.client.speaker;

public record CreateSpeakerEnrollmentCommand(
    String tenantId,
    String speakerProfileId,
    String sourceAudioFileId,
    String createdBy,
    String requestId,
    String traceId,
    String idempotencyKey
) {
}
