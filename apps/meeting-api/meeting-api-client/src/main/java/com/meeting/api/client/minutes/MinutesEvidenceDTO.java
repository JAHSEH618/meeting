package com.meeting.api.client.minutes;

public record MinutesEvidenceDTO(
    String segmentId,
    Long startMs,
    Long endMs,
    String evidenceTextSnapshot
) {
}
