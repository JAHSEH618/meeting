package com.meeting.api.client.speaker;

import java.time.OffsetDateTime;
import java.util.List;

public record MeetingSpeakerDTO(
    String speakerLabel,
    String displayName,
    String personId,
    String speakerProfileId,
    String confirmationStatus,
    Double autoMatchScore,
    OffsetDateTime confirmedAt,
    List<String> candidatePersonIds
) {
}
