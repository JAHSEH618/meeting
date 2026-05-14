package com.meeting.api.client.transcript;

public record UpdateSegmentResult(
    String segmentId,
    int transcriptVersion,
    String editStatus,
    boolean downstreamStaleMarked
) {
}
