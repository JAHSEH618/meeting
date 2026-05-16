package com.meeting.api.client.rag;

/**
 * Polymorphic citation surfaced in a RAG answer.
 *
 * <p>Sealed interface — the adapter serializes the two permitted
 * variants as the {@code MEETING_SEGMENT} / {@code DOCUMENT_CHUNK}
 * discriminated objects defined in {@code openapi/public-api.yaml}.
 */
public sealed interface RagCitationDTO
    permits MeetingSegmentCitationDTO, DocumentChunkCitationDTO {

    /** The {@code knowledge_chunks.id} that backed this citation. */
    String chunkId();

    /** Wire-level discriminator: {@code MEETING_SEGMENT} or {@code DOCUMENT_CHUNK}. */
    String type();
}
