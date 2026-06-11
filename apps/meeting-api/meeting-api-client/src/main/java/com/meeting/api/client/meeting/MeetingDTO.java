package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import java.time.OffsetDateTime;
import java.util.List;

public record MeetingDTO(
    String meetingId,
    String tenantId,
    String title,
    OffsetDateTime scheduledStartAt,
    SecurityLevel securityLevel,
    MeetingStatus status,
    String language,
    int transcriptVersion,
    int minutesVersion,
    OffsetDateTime createdAt,
    List<ParticipantDTO> participants
) {
    public MeetingDTO(
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
        this(
            meetingId,
            tenantId,
            title,
            null,
            securityLevel,
            status,
            language,
            transcriptVersion,
            minutesVersion,
            createdAt,
            List.of()
        );
    }

    public record ParticipantDTO(
        String personId,
        String displayName,
        String role
    ) {
    }
}
