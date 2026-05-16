package com.meeting.api.client.rag;

/**
 * Citation pointing at a transcript segment of a meeting.
 *
 * <p>{@code chunkId} is the {@code knowledge_chunks.id} that surfaced
 * the citation; {@code segmentId} is the upstream transcript segment
 * the chunk was derived from (the UI uses this to deep-link into the
 * transcript view at the right offset).
 */
public record MeetingSegmentCitationDTO(
    String chunkId,
    String meetingId,
    String meetingTitle,
    String segmentId,
    String speaker,
    long startMs,
    long endMs,
    String content
) implements RagCitationDTO {

    @Override
    public String type() {
        return "MEETING_SEGMENT";
    }
}
