package com.meeting.api.domain.speaker;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SpeakerProfileRepository {
    SpeakerProfile save(SpeakerProfile profile);

    Optional<SpeakerProfile> findById(String tenantId, String profileId);

    List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked);

    /** Bulk lookup for callback-time membership checks (authorized profile scope). */
    List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds);

    void updateConsentStatus(String tenantId, String profileId, String consentStatus,
                              OffsetDateTime revokedAt, OffsetDateTime deletedAt, OffsetDateTime updatedAt);
}
