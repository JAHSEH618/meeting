package com.meeting.api.domain.kms;

/**
 * Encrypted speaker embedding payload ready for persistence in {@code speaker_embeddings}.
 *
 * Plaintext embedding bytes are never carried by this type. The {@code ciphertext}
 * contains the GCM ciphertext concatenated with the authentication tag. The
 * {@code nonce} is the 12-byte IV that must be reused for decryption. The
 * {@code wrappedDek} is the KMS-wrapped data key (no plaintext key material).
 */
public record EncryptedEmbedding(
    byte[] ciphertext,
    byte[] nonce,
    byte[] wrappedDek,
    String keyId,
    String algorithm,
    String plaintextHash
) {
}
