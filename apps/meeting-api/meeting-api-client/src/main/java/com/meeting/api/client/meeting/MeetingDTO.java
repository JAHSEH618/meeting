package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import java.time.OffsetDateTime;

public record MeetingDTO(
    String meetingId,
    String tenantId,
    String title,
    SecurityLevel securityLevel,
    MeetingStatus status,
    String language,
    int transcriptVersion,
    int minutesVersion,
    OffsetDateTime createdAt
) {
}
