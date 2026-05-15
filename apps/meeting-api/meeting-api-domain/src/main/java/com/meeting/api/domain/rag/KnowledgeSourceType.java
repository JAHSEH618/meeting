package com.meeting.api.domain.rag;

/**
 * Origin of a {@link KnowledgeChunk}, recorded as the {@code source_type}
 * column on {@code knowledge_chunks} and propagated to ai-worker's
 * {@code RerankCandidate.sourceType} for citation rendering.
 *
 * <p>Values mirror the {@code SourceType} enum in
 * {@code ai-worker-internal-api.yaml} but live in domain to keep
 * Spring / Gson / OpenAPI generator dependencies out of the model layer.
 */
public enum KnowledgeSourceType {
    PRIMARY_TRANSCRIPT,
    AI_SUMMARY,
    DECISION,
    ACTION_ITEM,
    RISK,
    DOCUMENT
}
