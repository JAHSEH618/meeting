package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.DocumentRole;
import java.time.OffsetDateTime;

public record MeetingDocumentDTO(
    String id,
    String meetingId,
    String documentId,
    String title,
    DocumentRole role,
    String attachedBy,
    OffsetDateTime attachedAt
) {
}
