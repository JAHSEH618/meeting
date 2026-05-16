package com.meeting.api.client.internal.callback;

import java.util.List;

/**
 * Command shape for {@code POST /internal/processing-tasks/{taskId}/embeddings}.
 *
 * <p>The worker submits a batch of text embeddings produced by bge-m3 (or its
 * configured replacement) for chunks already persisted in {@code knowledge_chunks}.
 * Meeting-api validates HMAC + idempotency + tenant linkage, then writes the
 * vectors into the existing chunk rows via the repository — the chunks are
 * already there, only the {@code embedding} column moves from NULL to a value.
 *
 * <p>{@code sourceType} is required by the underlying OpenAPI contract for
 * audit logging and metric tagging; it does not gate persistence (the
 * per-chunk source type is already on the chunk row).
 */
public record EmbeddingsCallbackCommand(
    CallbackMetadata metadata,
    String tenantId,
    String taskId,
    int attemptNo,
    String embeddingBatchId,
    String sourceType,
    String embeddingModelVersion,
    String chunkStrategyVersion,
    List<Item> items
) {
    public record Item(
        String chunkId,
        String sourceId,
        Integer sourceVersion,
        String contentHash,
        Embedding embedding
    ) {
    }

    public record Embedding(
        String format,
        int dimension,
        float[] values
    ) {
        public Embedding {
            if (values == null) {
                throw new IllegalArgumentException("embedding values must not be null");
            }
            values = values.clone();
        }

        @Override
        public float[] values() {
            return values.clone();
        }
    }
}
