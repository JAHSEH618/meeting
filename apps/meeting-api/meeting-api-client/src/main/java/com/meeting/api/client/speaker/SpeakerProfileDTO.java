package com.meeting.api.client.speaker;

import java.time.OffsetDateTime;

public record SpeakerProfileDTO(
    String speakerProfileId,
    String tenantId,
    String personId,
    String displayName,
    String consentStatus,
    String consentSource,
    String consentVersion,
    OffsetDateTime revokedAt,
    OffsetDateTime deletedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
