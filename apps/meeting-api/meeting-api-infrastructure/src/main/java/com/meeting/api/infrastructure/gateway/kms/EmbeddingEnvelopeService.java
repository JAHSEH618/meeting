package com.meeting.api.infrastructure.gateway.kms;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.kms.EmbeddingEnvelopeGateway;
import com.meeting.api.domain.kms.EncryptedEmbedding;
import com.meeting.api.domain.kms.KmsGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Envelope-encrypts speaker embeddings (float[] -> AES-256-GCM ciphertext).
 *
 * <p>Per spec §4.3: AES-256-GCM, 12-byte nonce, 128-bit (16-byte) tag, data key wrapped
 * via {@link KmsGateway}. The plaintext DEK is zeroized after every operation.</p>
 *
 * <p>The plaintext SHA-256 hash is captured for {@code embedding_hash} integrity checks
 * without ever persisting plaintext.</p>
 */
@Component
public class EmbeddingEnvelopeService implements EmbeddingEnvelopeGateway {
    private static final String ALGORITHM = "AES-256-GCM";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KmsGateway kmsGateway;
    private final SecureRandom random = new SecureRandom();

    public EmbeddingEnvelopeService(KmsGateway kmsGateway) {
        this.kmsGateway = kmsGateway;
    }

    public EncryptedEmbedding encrypt(String tenantId, float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding must not be empty");
        }
        byte[] plaintext = floatsToBytes(embedding);
        try {
            return encryptBytes(tenantId, plaintext);
        } finally {
            zero(plaintext);
        }
    }

    public float[] decrypt(String tenantId, EncryptedEmbedding payload) {
        byte[] dek = kmsGateway.unwrapDataKey(tenantId, payload.keyId(), payload.wrappedDek());
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, payload.nonce()));
            byte[] plaintext = cipher.doFinal(payload.ciphertext());
            try {
                String observedHash = sha256Hex(plaintext);
                if (payload.plaintextHash() != null && !payload.plaintextHash().equals(observedHash)) {
                    throw new LlmProviderException(ErrorCode.KMS_KEY_UNAVAILABLE, "embedding hash mismatch on decrypt");
                }
                return bytesToFloats(plaintext);
            } finally {
                zero(plaintext);
            }
        } catch (LlmProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmProviderException(ErrorCode.KMS_KEY_UNAVAILABLE, "embedding decryption failed", ex);
        } finally {
            zero(dek);
        }
    }

    private EncryptedEmbedding encryptBytes(String tenantId, byte[] plaintext) {
        KmsGateway.GeneratedDataKey dek = kmsGateway.generateDataKey(tenantId);
        byte[] plaintextDek = dek.plaintextDek();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(plaintextDek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext);
            String hash = sha256Hex(plaintext);
            return new EncryptedEmbedding(
                ciphertextWithTag,
                nonce,
                dek.wrappedDek(),
                dek.keyId(),
                ALGORITHM,
                hash
            );
        } catch (Exception ex) {
            throw new LlmProviderException(ErrorCode.KMS_KEY_UNAVAILABLE, "embedding encryption failed", ex);
        } finally {
            zero(plaintextDek);
        }
    }

    private static byte[] floatsToBytes(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    private static float[] bytesToFloats(byte[] bytes) {
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("embedding bytes length not aligned to float boundary");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void zero(byte[] buffer) {
        if (buffer != null) {
            Arrays.fill(buffer, (byte) 0);
        }
    }
}
