package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record MeetingDTO(
    String meetingId,
    String tenantId,
    String title,
    OffsetDateTime scheduledStartAt,
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
