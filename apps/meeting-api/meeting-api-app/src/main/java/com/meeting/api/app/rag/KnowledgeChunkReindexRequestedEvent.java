package com.meeting.api.app.rag;

import com.meeting.api.client.enums.SecurityLevel;
import java.util.List;
import java.util.Objects;

/**
 * Internal Spring application event published by {@link ChunkingApplicationService}
 * after a re-chunk transaction commits. Consumed by {@link EmbeddingTaskDispatcher},
 * which fans out the chunk batch into one or more TEXT_EMBEDDING processing tasks
 * (one task per batch of at most {@code EmbeddingTaskDispatcher.MAX_CHUNKS_PER_TASK}).
 *
 * <p>This is not a {@code DomainEvent}; it never reaches the outbox. It exists only
 * to keep ChunkingApplicationService decoupled from ProcessingTask plumbing while
 * preserving the strong "the chunks have been persisted before any embed task is
 * enqueued" ordering guarantee (the listener runs {@code AFTER_COMMIT}).
 *
 * <p>Either {@code meetingId} or {@code documentId} must be non-null and the other
 * must be null — chunks belong to exactly one of the two.
 */
public record KnowledgeChunkReindexRequestedEvent(
    String tenantId,
    String meetingId,
    String documentId,
    List<String> chunkIds,
    SecurityLevel securityLevel,
    String chunkStrategyVersion,
    Integer transcriptVersion,
    Integer minutesVersion,
    String traceId
) {
    public KnowledgeChunkReindexRequestedEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(securityLevel, "securityLevel");
        Objects.requireNonNull(chunkStrategyVersion, "chunkStrategyVersion");
        if ((meetingId == null) == (documentId == null)) {
            throw new IllegalArgumentException(
                "exactly one of meetingId / documentId must be set: meetingId="
                    + meetingId + " documentId=" + documentId);
        }
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
    }

    public boolean hasWork() {
        return !chunkIds.isEmpty();
    }
}
