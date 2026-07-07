package com.meeting.api.domain.rag;

/**
 * Value object describing how a body of text is sliced into RAG chunks.
 *
 * <p>The default {@link #DEFAULT_ZH} matches the bge-m3 sweet spot for
 * Chinese transcripts (≈512 char window, 64 char overlap). Changing
 * {@link #name} bumps every chunk's {@code chunk_strategy_version} on
 * next reindex, which in turn invalidates the upstream RAG cache.
 */
public record ChunkStrategy(
    String name,
    int maxTokens,
    int overlapTokens,
    String tokenizer
) {
    public static final ChunkStrategy DEFAULT_ZH = new ChunkStrategy(
        "default-zh-v2", 512, 64, "chinese-char"
    );

    public ChunkStrategy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ChunkStrategy.name must not be blank");
        }
        if (maxTokens < 32 || maxTokens > 4096) {
            throw new IllegalArgumentException(
                "ChunkStrategy.maxTokens=" + maxTokens + " out of range [32,4096]"
            );
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException(
                "ChunkStrategy.overlapTokens=" + overlapTokens
                    + " must be in [0, maxTokens) (maxTokens=" + maxTokens + ")"
            );
        }
        if (tokenizer == null || tokenizer.isBlank()) {
            throw new IllegalArgumentException("ChunkStrategy.tokenizer must not be blank");
        }
    }
}
