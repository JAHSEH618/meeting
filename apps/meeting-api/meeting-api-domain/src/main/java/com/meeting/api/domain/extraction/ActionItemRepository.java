package com.meeting.api.domain.extraction;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.List;

public interface ActionItemRepository {
    String save(ActionItemRecord record);

    List<ActionItemRecord> findByMeeting(String tenantId, String meetingId);

    void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now);

    void markStaleForMeeting(String tenantId, String meetingId);

    record ActionItemRecord(
        String id,
        String tenantId,
        String meetingId,
        String origin,
        String title,
        String description,
        String ownerPersonId,
        String ownerRawText,
        String deadlineRawText,
        OffsetDateTime deadlineParsed,
        String priority,
        String status,
        String acceptanceStatus,
        Integer sourceTranscriptVersion,
        StaleStatus staleStatus,
        List<EvidenceJson> evidence,
        String artifactManifestId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
    }

    record EvidenceJson(
        String segmentId,
        Long startMs,
        Long endMs,
        String evidenceTextSnapshot
    ) {
    }
}
