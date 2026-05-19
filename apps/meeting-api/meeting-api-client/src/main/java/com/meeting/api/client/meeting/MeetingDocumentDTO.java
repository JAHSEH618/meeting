package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.client.enums.SecurityLevel;
import java.time.OffsetDateTime;

public record MeetingDocumentDTO(
    String id,
    String meetingId,
    String documentId,
    String title,
    DocumentRole role,
    SecurityLevel securityLevel,
    String attachedBy,
    OffsetDateTime attachedAt
) {
}
