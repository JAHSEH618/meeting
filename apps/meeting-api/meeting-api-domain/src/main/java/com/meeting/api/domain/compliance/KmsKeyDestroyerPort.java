package com.meeting.api.domain.compliance;

import java.util.List;

/**
 * Port for destroying KMS data-encryption keys (DEKs) tied to a
 * deletion target. Separate from {@code KmsGateway} (which only
 * generates / unwraps) so the executor can swap in a real
 * vault-aware destroyer without touching the encryption path.
 *
 * <p>Phase 1 ships a NoOp implementation that records the requested
 * key ids without actually contacting KMS — the deletion-job
 * certificate hash still captures the intent. Phase 2+ replaces it
 * with a vault-backed destroyer.
 */
public interface KmsKeyDestroyerPort {

    /**
     * Destroy all DEKs associated with a speaker profile (one per
     * stored embedding row). Implementations should be idempotent —
     * a re-run with the same profile yields the same result list.
     *
     * @return the key ids that were (logically) destroyed.
     */
    List<String> destroyForSpeakerProfile(String tenantId, String speakerProfileId);

    /**
     * Destroy DEKs tied to a user (transitively all their owned
     * resources). Empty list for phase 1.
     */
    default List<String> destroyForUser(String tenantId, String userId) {
        return List.of();
    }
}
