package com.meeting.api.domain.speaker;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SpeakerEnrollmentRepository {
    String save(SpeakerEnrollmentRecord record);

    Optional<SpeakerEnrollmentRecord> findById(String tenantId, String enrollmentId);

    List<SpeakerEnrollmentRecord> findByProfile(String tenantId, String profileId);

    void updateStatus(String tenantId, String enrollmentId, String enrollmentStatus,
                       Double qualityScore, String modelVersion, String errorCode, OffsetDateTime now);

    record SpeakerEnrollmentRecord(
        String id,
        String tenantId,
        String speakerProfileId,
        String sourceAudioFileId,
        String enrollmentStatus,
        Double qualityScore,
        String modelVersion,
        String artifactUri,
        String errorCode,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
    }
}
