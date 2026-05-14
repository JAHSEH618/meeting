package com.meeting.api.domain.extraction;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.List;

public interface DecisionRepository {
    String save(DecisionRecord record);

    List<DecisionRecord> findByMeeting(String tenantId, String meetingId);

    void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now);

    void markStaleForMeeting(String tenantId, String meetingId);

    record DecisionRecord(
        String id,
        String tenantId,
        String meetingId,
        String title,
        String description,
        String status,
        String acceptanceStatus,
        Integer sourceTranscriptVersion,
        StaleStatus staleStatus,
        List<ActionItemRepository.EvidenceJson> evidence,
        String artifactManifestId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
    }
}
