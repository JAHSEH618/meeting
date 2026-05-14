package com.meeting.api.domain.kms;

/**
 * KMS abstraction for envelope encryption of speaker embeddings.
 *
 * Plaintext data-encryption keys (DEKs) are short-lived in process memory; only the
 * KMS-wrapped form is persisted alongside the ciphertext. Callers must clear plaintext
 * DEK buffers as soon as encryption/decryption completes (best-effort zeroization).
 */
public interface KmsGateway {
    /** Generate a fresh 256-bit DEK and return both plaintext and KMS-wrapped form. */
    GeneratedDataKey generateDataKey(String tenantId);

    /** Unwrap a previously generated DEK using the same key version. */
    byte[] unwrapDataKey(String tenantId, String keyId, byte[] wrappedDek);

    record GeneratedDataKey(byte[] plaintextDek, byte[] wrappedDek, String keyId) {
    }
}
