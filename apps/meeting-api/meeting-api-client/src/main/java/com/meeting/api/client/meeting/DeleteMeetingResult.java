package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import java.time.OffsetDateTime;

/**
 * Result of {@code DELETE /api/meetings/{meetingId}}. Returned with HTTP 200
 * after the meeting has been soft-deleted (status flipped to {@code DELETED}).
 */
public record DeleteMeetingResult(
    String meetingId,
    MeetingStatus status,
    OffsetDateTime deletedAt
) {
}
