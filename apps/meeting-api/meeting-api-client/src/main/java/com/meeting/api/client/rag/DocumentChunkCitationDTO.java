package com.meeting.api.client.rag;

/**
 * Citation pointing at a parsed-document chunk.
 *
 * <p>{@code page} is the 1-indexed page number captured at document
 * parse time; {@code 0} signals "unknown / not paginated" (the OpenAPI
 * contract uses a plain integer so we keep the zero sentinel rather
 * than a nullable on the client side).
 */
public record DocumentChunkCitationDTO(
    String chunkId,
    String documentId,
    String documentTitle,
    int page,
    String content
) implements RagCitationDTO {

    @Override
    public String type() {
        return "DOCUMENT_CHUNK";
    }
}
