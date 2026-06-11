package com.meeting.api.app.rag;

import java.util.List;
import java.util.Objects;

/**
 * Internal Spring application event published by {@link ChunkingApplicationService}
 * after a re-chunk transaction commits. Consumed by {@link EmbeddingTaskDispatcher},
 * which fans out the chunk batch into one or more TEXT_EMBEDDING processing tasks
 * (one task per batch of at most {@code EmbeddingTaskDispatcher.MAX_CHUNKS_PER_TASK}).
 *
 * <p>The {@link ChunkRef}s carry both id and content so the dispatcher can put the
 * raw text into the task message — that way ai-worker can run bge-m3 without a
 * second round-trip back to Java just to read the just-persisted chunk content.
 * Text is bounded by {@code ChunkStrategy.maxTokens} (512 char window in the
 * default config), so a 32-chunk task stays under ~50KB.
 *
 * <p>This is not a {@code DomainEvent}; it never reaches the outbox. It exists
 * only to keep ChunkingApplicationService decoupled from ProcessingTask plumbing
 * while preserving the strong "the chunks have been persisted before any embed
 * task is enqueued" ordering guarantee (the listener runs {@code AFTER_COMMIT}).
 *
 * <p>Either {@code meetingId} or {@code documentId} must be non-null and the other
 * must be null — chunks belong to exactly one of the two.
 */
public record KnowledgeChunkReindexRequestedEvent(
    String tenantId,
    String meetingId,
    String documentId,
    List<ChunkRef> chunks,
    String chunkStrategyVersion,
    Integer transcriptVersion,
    Integer minutesVersion,
    String traceId
) {
    public KnowledgeChunkReindexRequestedEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(chunkStrategyVersion, "chunkStrategyVersion");
        if ((meetingId == null) == (documentId == null)) {
            throw new IllegalArgumentException(
                "exactly one of meetingId / documentId must be set: meetingId="
                    + meetingId + " documentId=" + documentId);
        }
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public boolean hasWork() {
        return !chunks.isEmpty();
    }

    public List<String> chunkIds() {
        return chunks.stream().map(ChunkRef::id).toList();
    }

    /** Identity + raw text of one chunk to embed. */
    public record ChunkRef(String id, String content) {
        public ChunkRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(content, "content");
            if (id.isBlank()) {
                throw new IllegalArgumentException("ChunkRef.id must not be blank");
            }
            if (content.isBlank()) {
                throw new IllegalArgumentException("ChunkRef.content must not be blank");
            }
        }
    }
}

