package com.meeting.api.client.meeting;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Command for {@code PATCH /api/meetings/{meetingId}}.
 *
 * <p>{@code participants == null} leaves the participant list unchanged;
 * an empty list replaces it with no participants. {@code expectedVersion}
 * checks the current transcript version, matching the existing meeting
 * delete/update concurrency field.
 */
public record UpdateMeetingCommand(
    String tenantId,
    String meetingId,
    String title,
    OffsetDateTime scheduledStartAt,
    boolean scheduledStartAtProvided,
    List<CreateMeetingCommand.ParticipantCommand> participants,
    Integer expectedVersion,
    String actorUserId,
    String requestId
) {
    public UpdateMeetingCommand(
        String tenantId,
        String meetingId,
        String title,
        OffsetDateTime scheduledStartAt,
        List<CreateMeetingCommand.ParticipantCommand> participants,
        Integer expectedVersion,
        String actorUserId,
        String requestId
    ) {
        this(
            tenantId,
            meetingId,
            title,
            scheduledStartAt,
            scheduledStartAt != null,
            participants,
            expectedVersion,
            actorUserId,
            requestId
        );
    }

    public UpdateMeetingCommand {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId must not be blank");
        }
    }
}
