package com.meeting.api.domain.rag;

/**
 * Lifecycle of a {@link KnowledgeChunk}, matching the {@code content_status}
 * Postgres enum on {@code knowledge_chunks}. {@code DELETED} chunks are
 * filtered out of every retrieval query but kept on the table for audit.
 */
public enum ChunkStatus {
    ACTIVE,
    DELETED
}
