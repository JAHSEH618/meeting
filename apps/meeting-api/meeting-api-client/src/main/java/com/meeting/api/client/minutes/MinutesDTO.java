package com.meeting.api.client.minutes;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record MinutesDTO(
    String minutesId,
    String tenantId,
    String meetingId,
    int minutesVersion,
    int sourceTranscriptVersion,
    String title,
    String markdown,
    List<MinutesSectionDTO> sections,
    String status,
    StaleStatus staleStatus,
    String artifactManifestId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
