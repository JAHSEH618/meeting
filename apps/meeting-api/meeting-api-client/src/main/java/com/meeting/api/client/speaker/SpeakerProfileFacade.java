package com.meeting.api.client.speaker;

import java.util.List;
import java.util.Optional;

public interface SpeakerProfileFacade {
    SpeakerProfileDTO create(CreateSpeakerProfileCommand command);

    Optional<SpeakerProfileDTO> get(String tenantId, String profileId);

    List<SpeakerProfileDTO> list(String tenantId);

    /** Soft revoke: marks consent_status REVOKED, sets revoked_at. Used by phase-4 cascade. */
    void revoke(String tenantId, String profileId, String revokedBy, String reason);

    /** Hard delete: marks deleted_at, consent_status DELETED. */
    void delete(String tenantId, String profileId, String deletedBy, String reason);

    SpeakerEnrollmentDTO addEnrollment(CreateSpeakerEnrollmentCommand command);

    List<SpeakerEnrollmentDTO> listEnrollments(String tenantId, String profileId);
}
