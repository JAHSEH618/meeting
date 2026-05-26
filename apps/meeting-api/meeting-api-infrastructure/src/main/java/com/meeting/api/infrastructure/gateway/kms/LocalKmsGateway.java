package com.meeting.api.infrastructure.gateway.kms;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.kms.KmsGateway;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dev/local {@link KmsGateway} backed by a single configured master key.
 * <p>
 * The master key is 32 bytes (AES-256) and loaded from {@code meeting.kms.master-key-base64}.
 * If unset, a random key is generated at startup with a warning (suitable only for tests).
 * Production replaces this bean with a KMS-backed implementation that calls the cloud KMS
 * over the wire and does not hold any master key in memory.
 */
@Component
public class LocalKmsGateway implements KmsGateway {
    private static final Logger log = LoggerFactory.getLogger(LocalKmsGateway.class);
    private static final String KEY_VERSION = "local-master-v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int DEK_BYTES = 32;

    private final SecretKeySpec masterKey;
    private final SecureRandom random;

    @Autowired
    public LocalKmsGateway(@Value("${meeting.kms.master-key-base64:}") String masterKeyBase64) {
        this(decodeOrGenerate(masterKeyBase64));
    }
    public LocalKmsGateway(byte[] masterKeyBytes) {
        if (masterKeyBytes.length != DEK_BYTES) {
            throw new IllegalArgumentException("master key must be exactly 32 bytes (AES-256)");
        }
        this.masterKey = new SecretKeySpec(masterKeyBytes, "AES");
        this.random = new SecureRandom();
    }

    @Override
    public GeneratedDataKey generateDataKey(String tenantId) {
        byte[] plaintext = new byte[DEK_BYTES];
        random.nextBytes(plaintext);
        byte[] wrapped = wrap(plaintext, tenantId);
        return new GeneratedDataKey(plaintext, wrapped, KEY_VERSION);
    }

    @Override
    public byte[] unwrapDataKey(String tenantId, String keyId, byte[] wrappedDek) {
        if (!KEY_VERSION.equals(keyId)) {
            throw new com.meeting.api.domain.llm.LlmProviderException(ErrorCode.KMS_KEY_UNAVAILABLE, "unsupported KMS key version: " + keyId);
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(wrappedDek, 0, nonce, 0, NONCE_BYTES);
            byte[] ciphertextWithTag = new byte[wrappedDek.length - NONCE_BYTES];
            System.arraycopy(wrappedDek, NONCE_BYTES, ciphertextWithTag, 0, ciphertextWithTag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(tenantId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertextWithTag);
        } catch (Exception ex) {
            throw new com.meeting.api.domain.llm.LlmProviderException(ErrorCode.KMS_KEY_UNAVAILABLE, "KMS unwrap failed", ex);
        }
    }

    private byte[] wrap(byte[] plaintextDek, String tenantId) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(tenantId.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertextWithTag = cipher.doFinal(plaintextDek);
            byte[] result = new byte[NONCE_BYTES + ciphertextWithTag.length];
            System.arraycopy(nonce, 0, result, 0, NONCE_BYTES);
            System.arraycopy(ciphertextWithTag, 0, result, NONCE_BYTES, ciphertextWithTag.length);
            return result;
        } catch (Exception ex) {
            throw new com.meeting.api.domain.llm.LlmProviderException(ErrorCode.KMS_KEY_UNAVAILABLE, "KMS wrap failed", ex);
        }
    }

    private static byte[] decodeOrGenerate(String base64) {
        if (base64 == null || base64.isBlank()) {
            log.warn("kms_master_key_unset using_ephemeral_random_key (dev only; production must set meeting.kms.master-key-base64)");
            byte[] rand = new byte[DEK_BYTES];
            new SecureRandom().nextBytes(rand);
            return rand;
        }
        byte[] decoded = Base64.getDecoder().decode(base64);
        if (decoded.length != DEK_BYTES) {
            throw new IllegalArgumentException("meeting.kms.master-key-base64 must decode to 32 bytes");
        }
        return decoded;
    }
}
