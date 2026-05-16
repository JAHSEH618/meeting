package com.meeting.api.adapter.rag;

import java.util.List;

/**
 * Request body mirror of the {@code RagQueryRequest} schema in
 * {@code openapi/public-api.yaml}. Fields are intentionally boxed so we
 * can distinguish "absent" from "explicit default" — actual default
 * values are applied in {@link RagQueryController}.
 */
public record RagQueryRequest(
    String question,
    Scope scope,
    Integer topN,
    Boolean includeStale
) {

    public record Scope(List<String> meetingIds, List<String> documentIds) {
    }
}
