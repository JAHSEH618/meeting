package com.meeting.api.client.speaker;

import java.time.OffsetDateTime;
import java.util.Optional;

public record SpeakerEnrollmentDTO(
    String enrollmentId,
    String speakerProfileId,
    String tenantId,
    String sourceAudioFileId,
    String enrollmentStatus,
    Double qualityScore,
    String modelVersion,
    String errorCode,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public Optional<Double> qualityScoreOptional() {
        return Optional.ofNullable(qualityScore);
    }
}
