package com.meeting.api.domain.speaker;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@code speaker_embeddings}. Stores envelope-encrypted ciphertext only.
 * Plaintext embeddings never enter this repository or any column it writes.
 */
public interface SpeakerEmbeddingRepository {
    void save(SpeakerEmbeddingRecord record);

    List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String speakerProfileId);

    /**
     * Batched variant of {@link #findByProfile}: fetch all embedding rows for the given
     * profile ids in a single query (avoids N+1 round-trips). Rows for all requested
     * profiles are returned together; callers group them by {@code speakerProfileId}.
     */
    List<SpeakerEmbeddingRecord> findByProfileIds(String tenantId, Collection<String> speakerProfileIds);

    /** Soft-revoke embeddings for a profile (cascade from profile revoke). */
    int revokeForProfile(String tenantId, String speakerProfileId, OffsetDateTime now);

    /** Soft-delete embeddings for a profile (cascade from profile delete). */
    int deleteForProfile(String tenantId, String speakerProfileId, OffsetDateTime now);

    record SpeakerEmbeddingRecord(
        String id,
        String tenantId,
        String speakerProfileId,
        String personId,
        String consentStatus,
        String encryptionKeyId,
        byte[] wrappedDataKey,
        String encryptionAlgorithm,
        byte[] embeddingCiphertext,
        String embeddingHash,
        String sourceAudioFileId,
        Double qualityScore,
        String modelVersion,
        OffsetDateTime revokedAt,
        OffsetDateTime deletedAt,
        OffsetDateTime createdAt
    ) {
    }
}
