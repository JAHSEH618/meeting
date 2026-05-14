package com.meeting.api.domain.extraction;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.List;

public interface RiskRepository {
    String save(RiskRecord record);

    List<RiskRecord> findByMeeting(String tenantId, String meetingId);

    void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now);

    void markStaleForMeeting(String tenantId, String meetingId);

    record RiskRecord(
        String id,
        String tenantId,
        String meetingId,
        String title,
        String description,
        String severity,
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
