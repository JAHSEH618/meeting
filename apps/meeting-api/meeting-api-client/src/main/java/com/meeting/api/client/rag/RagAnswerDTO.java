package com.meeting.api.client.rag;

import com.meeting.api.client.enums.RagAnswerCoverage;
import java.util.List;

/**
 * Result of {@link RagQueryFacade#query}.
 *
 * <ul>
 *   <li>{@code answer} — the markdown the LLM produced (or a degraded
 *       placeholder when no chunks were authorized to reach the model).</li>
 *   <li>{@code citations} — the subset of authorized chunks the model
 *       used as evidence, polymorphic by {@link RagCitationDTO}.</li>
 *   <li>{@code coverage} — {@code FULL} if any document chunk fed the
 *       answer, else {@code TRANSCRIPT_ONLY}; lets the UI badge
 *       answers backed only by transcript content.</li>
 *   <li>{@code artifactManifestId} — pointer back to the
 *       {@code llm_call_logs} / {@code artifact_manifests} row so the
 *       answer can be audited / re-fetched.</li>
 * </ul>
 */
public record RagAnswerDTO(
    String answer,
    List<RagCitationDTO> citations,
    RagAnswerCoverage coverage,
    String artifactManifestId
) {

    public RagAnswerDTO {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
