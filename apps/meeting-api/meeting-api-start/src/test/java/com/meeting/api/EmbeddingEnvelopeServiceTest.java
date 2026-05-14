package com.meeting.api;

import com.meeting.api.domain.kms.EncryptedEmbedding;
import com.meeting.api.domain.kms.KmsGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.infrastructure.gateway.kms.EmbeddingEnvelopeService;
import com.meeting.api.infrastructure.gateway.kms.LocalKmsGateway;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingEnvelopeServiceTest {

    @Test
    void encryptThenDecryptRoundTripsTheEmbedding() {
        KmsGateway kms = newLocalKms();
        EmbeddingEnvelopeService service = new EmbeddingEnvelopeService(kms);
        float[] embedding = new float[]{0.12f, -0.34f, 1.0f, 2.5f, -7.1f};

        EncryptedEmbedding payload = service.encrypt("tenant_01", embedding);

        assertThat(payload.algorithm()).isEqualTo("AES-256-GCM");
        assertThat(payload.nonce()).hasSize(12);
        // ciphertext length = plaintext bytes + 16-byte tag
        assertThat(payload.ciphertext()).hasSize(embedding.length * Float.BYTES + 16);
        assertThat(payload.keyId()).isEqualTo("local-master-v1");
        assertThat(payload.plaintextHash()).hasSize(64);
        assertThat(payload.wrappedDek()).isNotEmpty();

        float[] decrypted = service.decrypt("tenant_01", payload);
        assertThat(decrypted).usingComparatorWithPrecision(1e-6f).containsExactly(embedding);
    }

    @Test
    void decryptDetectsCiphertextTamper() {
        EmbeddingEnvelopeService service = new EmbeddingEnvelopeService(newLocalKms());
        EncryptedEmbedding payload = service.encrypt("tenant_01", new float[]{1.0f, 2.0f, 3.0f});
        byte[] tampered = payload.ciphertext().clone();
        tampered[0] ^= 0x01;
        EncryptedEmbedding bad = new EncryptedEmbedding(
            tampered, payload.nonce(), payload.wrappedDek(), payload.keyId(), payload.algorithm(), payload.plaintextHash()
        );

        assertThatThrownBy(() -> service.decrypt("tenant_01", bad))
            .isInstanceOf(LlmProviderException.class);
    }

    @Test
    void decryptDetectsTenantMismatch() {
        EmbeddingEnvelopeService service = new EmbeddingEnvelopeService(newLocalKms());
        EncryptedEmbedding payload = service.encrypt("tenant_01", new float[]{1.0f, 2.0f});

        assertThatThrownBy(() -> service.decrypt("tenant_02", payload))
            .isInstanceOf(LlmProviderException.class);
    }

    @Test
    void differentInvocationsProduceFreshNoncesAndDistinctCiphertexts() {
        EmbeddingEnvelopeService service = new EmbeddingEnvelopeService(newLocalKms());
        float[] embedding = new float[]{0.5f, 1.5f, -2.5f};

        EncryptedEmbedding first = service.encrypt("tenant_01", embedding);
        EncryptedEmbedding second = service.encrypt("tenant_01", embedding);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.plaintextHash()).isEqualTo(second.plaintextHash());
    }

    @Test
    void emptyEmbeddingRejected() {
        EmbeddingEnvelopeService service = new EmbeddingEnvelopeService(newLocalKms());
        assertThatThrownBy(() -> service.encrypt("tenant_01", new float[0]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localKmsRejectsWrongKeyVersion() {
        LocalKmsGateway kms = newLocalKms();
        EmbeddingEnvelopeService service = new EmbeddingEnvelopeService(kms);
        EncryptedEmbedding payload = service.encrypt("tenant_01", new float[]{1.0f});

        EncryptedEmbedding badVersion = new EncryptedEmbedding(
            payload.ciphertext(), payload.nonce(), payload.wrappedDek(),
            "different-version", payload.algorithm(), payload.plaintextHash()
        );
        assertThatThrownBy(() -> service.decrypt("tenant_01", badVersion))
            .isInstanceOf(LlmProviderException.class);
    }

    private static LocalKmsGateway newLocalKms() {
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        return new LocalKmsGateway(master);
    }

    // Side-effect: keep an unused reference to avoid the import lint.
    @SuppressWarnings("unused")
    private static final byte[] UNUSED = new byte[0];
    static {
        Arrays.fill(UNUSED, (byte) 0);
    }
}
