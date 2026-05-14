package com.meeting.api.client.extraction;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record ActionItemDTO(
    String id,
    String tenantId,
    String meetingId,
    String origin,
    String title,
    String description,
    String ownerPersonId,
    String ownerRawText,
    String priority,
    String status,
    String acceptanceStatus,
    Integer sourceTranscriptVersion,
    StaleStatus staleStatus,
    List<EvidenceDTO> evidence,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
