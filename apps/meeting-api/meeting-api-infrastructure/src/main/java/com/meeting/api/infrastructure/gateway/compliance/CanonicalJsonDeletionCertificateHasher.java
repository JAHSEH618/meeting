package com.meeting.api.infrastructure.gateway.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.meeting.api.domain.compliance.DeletionCertificateHasher;
import com.meeting.api.domain.compliance.DeletionExecutorPort.DeletionOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Canonical-JSON SHA-256 implementation. The map ordering is fixed
 * (tenantId → jobId → deletedRows → deletedFiles → kmsKeysDestroyed →
 * failedItems) and the {@code ObjectMapper} is configured with
 * {@code ORDER_MAP_ENTRIES_BY_KEYS} so nested maps serialize
 * deterministically. Running the same outcome through this twice
 * yields identical bytes → identical digest.
 */
@Component
public class CanonicalJsonDeletionCertificateHasher implements DeletionCertificateHasher {

    private final ObjectMapper canonicalMapper;

    public CanonicalJsonDeletionCertificateHasher() {
        this.canonicalMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Override
    public String compute(String tenantId, String jobId, DeletionOutcome outcome) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenantId", tenantId);
        root.put("jobId", jobId);
        root.put("deletedRows", outcome.deletedRows());
        root.put("deletedFiles", outcome.deletedFiles());
        root.put("kmsKeysDestroyed", outcome.kmsKeysDestroyed());
        root.put("failedItems", outcome.failedItems());

        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(root);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("canonical JSON serialization failed", ex);
        }
    }

    // Helper unused outside this class; surfaced for tests that want
    // to inspect the exact bytes the hash was computed over.
    byte[] canonicalBytes(String tenantId, String jobId, DeletionOutcome outcome) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenantId", tenantId);
        root.put("jobId", jobId);
        root.put("deletedRows", outcome.deletedRows());
        root.put("deletedFiles", outcome.deletedFiles());
        root.put("kmsKeysDestroyed", outcome.kmsKeysDestroyed());
        root.put("failedItems", outcome.failedItems());
        try {
            return canonicalMapper.writeValueAsBytes(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // Force a constant since UTF-8 is implied; kept private to avoid
    // accidental external callers relying on a specific charset.
    @SuppressWarnings("unused")
    private static final java.nio.charset.Charset CANONICAL_CHARSET = StandardCharsets.UTF_8;
}
