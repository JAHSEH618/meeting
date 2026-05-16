package com.meeting.api.domain.rag;

import java.util.Map;
import java.util.Set;

/**
 * Loads the per-owner metadata the RAG query layer needs to turn raw
 * {@link KnowledgeChunkCandidate}s into rich citation DTOs:
 *
 * <ul>
 *   <li>meeting titles, so a {@code MEETING_SEGMENT} citation can be
 *       labelled with the human-readable meeting name;</li>
 *   <li>transcript segments (speaker label, start/end offsets) so the
 *       UI can deep-link into the transcript view at the right offset;</li>
 *   <li>document titles + page numbers so a {@code DOCUMENT_CHUNK}
 *       citation can render alongside the source document name.</li>
 * </ul>
 *
 * <p>Three small batch reads keep the per-query latency bounded — one
 * round-trip per owner type rather than one per chunk. The contract is
 * "best effort": owners that no longer exist (deleted between retrieval
 * and enrichment) simply drop out of the returned maps, and callers
 * MUST treat a missing key as "data unavailable" and degrade gracefully
 * rather than failing the whole query.
 *
 * <p>This port deliberately lives in {@code domain.rag} alongside the
 * other RAG ports: the JDBC implementation in
 * {@code infrastructure.persistence.rag} owns the SQL, and the
 * application service depends only on this interface.
 */
public interface RagCitationEnricher {

    /**
     * Look up the display titles of the given meeting IDs.
     *
     * @return a map from meetingId → title; missing keys mean the
     *     meeting could not be loaded (deleted, RLS-hidden, etc.).
     */
    Map<String, String> loadMeetingTitles(String tenantId, Set<String> meetingIds);

    /**
     * Look up the display titles of the given document IDs.
     *
     * @return a map from documentId → title; missing keys mean the
     *     document could not be loaded.
     */
    Map<String, String> loadDocumentTitles(String tenantId, Set<String> documentIds);

    /**
     * Look up speaker / offset metadata for transcript segments. The
     * caller passes the segment IDs that originated chunk citations
     * (i.e. {@code KnowledgeChunkCandidate.sourceSegmentId()} for
     * meeting-side chunks). The implementation reads the latest
     * version per segment — RAG citations are stamped at query time,
     * not pinned to a transcript version, because the UI rerenders
     * citation hits from the current transcript anyway.
     *
     * @return a map from segmentId → {@link TranscriptSegmentInfo};
     *     missing keys mean the segment row was not found.
     */
    Map<String, TranscriptSegmentInfo> loadTranscriptSegments(String tenantId, Set<String> segmentIds);

    /**
     * Look up the {@code pageNumber} of each {@code document_chunks}
     * row keyed by its ID. Used to enrich {@code DOCUMENT_CHUNK}
     * citations whose backing knowledge-chunk recorded
     * {@code sourceId = "<documentChunkId>#<sub>"}.
     *
     * @return a map from documentChunkId → 1-indexed page number, or
     *     missing key if the chunk row was not found / had no page.
     */
    Map<String, Integer> loadDocumentChunkPages(String tenantId, Set<String> documentChunkIds);

    /** Minimal projection of a {@code transcript_segments} row. */
    record TranscriptSegmentInfo(
        String segmentId,
        String speakerLabel,
        String speakerDisplayName,
        long startMs,
        long endMs
    ) {
        public String displaySpeaker() {
            return speakerDisplayName == null || speakerDisplayName.isBlank()
                ? speakerLabel
                : speakerDisplayName;
        }
    }
}
