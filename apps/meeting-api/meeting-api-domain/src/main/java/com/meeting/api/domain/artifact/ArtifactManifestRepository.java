package com.meeting.api.domain.artifact;

import java.time.OffsetDateTime;

/**
 * Audit ledger that records every AI-produced artifact (LLM-generated
 * minutes / extraction / RAG answer; later: ASR results, chunking runs,
 * export renders) with enough provenance to reproduce or audit the
 * output: input hash, prompt template + version, provider + model
 * version, pipeline + code version, and the active data-boundary
 * policy.
 *
 * <p>Every domain row that points back to its producer (e.g. {@code
 * meeting_minutes.artifact_manifest_id}) has a FK to this table, so a
 * manifest row MUST be inserted in the same transaction as the artifact
 * row to satisfy the constraint. The spec (§12.5 #10) requires that any
 * AI result be traceable back to its manifest.
 *
 * <p>Phase-1: only the LLM gateway writes manifests. Future producers
 * (ASR / chunking / export) will reuse the same port without breaking
 * the table contract.
 */
public interface ArtifactManifestRepository {

    /**
     * Persist a manifest row. Returns the row id (echoes the input
     * {@link ArtifactManifestRecord#id()}).
     */
    String save(ArtifactManifestRecord record);

    record ArtifactManifestRecord(
        String id,
        String tenantId,
        String meetingId,                   // nullable
        String taskId,                      // nullable
        String artifactType,                // e.g. LLM_MINUTES, LLM_EXTRACTION, LLM_RAG_ANSWER
        String artifactUri,                 // nullable; populated by TOS-writing producers
        String artifactHash,                // nullable; e.g. output sha256
        String inputArtifactHash,           // sha256 of the rendered input
        String inputJson,                   // JSON snapshot of input variables / refs
        String outputJson,                  // JSON snapshot of output (size-bounded)
        String modelsJson,                  // JSON array of {role, modelVersion} entries
        String promptTemplateId,
        String promptTemplateVersion,
        String provider,                    // e.g. "dashscope"
        String modelVersion,                // resolved by provider
        String pipelineVersion,             // nullable
        String codeVersion,                 // nullable
        String dataBoundaryPolicyVersion,   // nullable
        OffsetDateTime createdAt
    ) {
    }
}
