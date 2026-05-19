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

    /**
     * Look up active profiles by person id (used by D7 reference-embedding endpoint;
     * filters out REVOKED / DELETED at the SQL layer). Default implementation
     * keeps in-memory test doubles compiling without a forced rewrite.
     */
    default List<SpeakerProfile> findByPersonIds(String tenantId, List<String> personIds) {
        throw new UnsupportedOperationException("findByPersonIds is not implemented");
    }

    void updateConsentStatus(String tenantId, String profileId, String consentStatus,
                              OffsetDateTime revokedAt, OffsetDateTime deletedAt, OffsetDateTime updatedAt);
}
