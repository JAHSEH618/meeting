package com.meeting.api.client.meeting;

import java.time.OffsetDateTime;
import java.util.List;

public record MeetingGlossaryDTO(
    String meetingId,
    List<GlossaryTermDTO> terms,
    OffsetDateTime updatedAt
) {
}
