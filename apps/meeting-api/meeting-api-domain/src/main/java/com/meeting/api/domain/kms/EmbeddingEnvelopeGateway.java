package com.meeting.api.domain.kms;

/**
 * Port for envelope-encrypting / decrypting speaker embeddings.
 * Implementations live in infrastructure and depend on {@link KmsGateway}.
 */
public interface EmbeddingEnvelopeGateway {
    EncryptedEmbedding encrypt(String tenantId, float[] embedding);

    float[] decrypt(String tenantId, EncryptedEmbedding payload);
}
