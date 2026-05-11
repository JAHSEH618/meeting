package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.SecurityLevel;
import java.time.OffsetDateTime;
import java.util.List;

public record CreateMeetingCommand(
    String tenantId,
    String title,
    OffsetDateTime scheduledStartAt,
    SecurityLevel securityLevel,
    String language,
    List<ParticipantCommand> participants,
    String createdBy
) {
    public record ParticipantCommand(
        String personId,
        String displayName,
        String role
    ) {
    }
}
