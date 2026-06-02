package com.meeting.api.client.speaker;

public record MeetingSpeakerCandidateDTO(
    String personId,
    String speakerProfileId,
    String displayName,
    Double confidence
) {
}
