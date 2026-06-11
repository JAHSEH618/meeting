package com.meeting.api.client.rag;


/**
 * Immutable, fully-validated input to {@link RagQueryFacade#query}.
 *
 * <p>Identity (tenant + user) are required for every
 * call — the second-pass authorization layer fails closed without them.
 * {@code requestId} / {@code traceId} are propagated to the LLM gateway
 * and the ai-worker rerank gateway so an end-to-end trace can be
 * reconstructed from logs.
 */
public record RagQueryCommand(
    String tenantId,
    String userId,
    String question,
    RagQueryScope scope,
    int topN,
    boolean includeStale,
    String requestId,
    String traceId
) {

    public RagQueryCommand {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (topN < 1 || topN > 20) {
            throw new IllegalArgumentException("topN must be in [1, 20]; was " + topN);
        }
        scope = scope == null ? RagQueryScope.EMPTY : scope;
    }
}
