package com.meeting.api.client.extraction;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record DecisionDTO(
    String id,
    String tenantId,
    String meetingId,
    String title,
    String description,
    String status,
    String acceptanceStatus,
    Integer sourceTranscriptVersion,
    StaleStatus staleStatus,
    List<EvidenceDTO> evidence,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
