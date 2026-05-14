package com.meeting.api.client.transcript;

import java.util.List;

public record TranscriptDTO(
    String meetingId,
    int transcriptVersion,
    String staleStatus,
    List<TranscriptSegmentDTO> segments
) {
}
