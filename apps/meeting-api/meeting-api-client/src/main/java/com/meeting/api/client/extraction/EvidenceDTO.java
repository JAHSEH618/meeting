package com.meeting.api.client.extraction;

public record EvidenceDTO(
    String segmentId,
    Long startMs,
    Long endMs,
    String evidenceTextSnapshot
) {
}
