package com.meeting.api.app.task;

import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.domain.task.CallbackNonceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit test for nonce deduplication in CallbackSecurityVerifier
 */
class CallbackSecurityVerifierNonceTest {

    private static final String SECRET = "test-hmac-secret";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 6, 13, 1, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @Test
    void shouldRejectReplayAttackWithDuplicateNonce() {
        InMemoryNonceRepo nonceRepo = new InMemoryNonceRepo();
        CallbackSecurityVerifier verifier = new CallbackSecurityVerifier(SECRET, 300, CLOCK, nonceRepo);

        CallbackMetadata metadata = validMetadata("nonce_001");

        // 第一次调用成功
        assertDoesNotThrow(() -> verifier.verify(metadata, "tenant_01", "worker_01", "task_01", "ASR"));

        // 第二次相同 nonce 应该拒绝
        assertThatThrownBy(() -> verifier.verify(metadata, "tenant_01", "worker_01", "task_01", "ASR"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonce already used");
    }

    @Test
    void shouldAllowDifferentNonces() {
        InMemoryNonceRepo nonceRepo = new InMemoryNonceRepo();
        CallbackSecurityVerifier verifier = new CallbackSecurityVerifier(SECRET, 300, CLOCK, nonceRepo);

        CallbackMetadata metadata1 = validMetadata("nonce_001");
        CallbackMetadata metadata2 = validMetadata("nonce_002");

        assertDoesNotThrow(() -> verifier.verify(metadata1, "tenant_01", "worker_01", "task_01", "ASR"));
        assertDoesNotThrow(() -> verifier.verify(metadata2, "tenant_01", "worker_01", "task_01", "DIARIZATION"));
    }

    @Test
    void shouldIsolateNoncesByTenant() {
        InMemoryNonceRepo nonceRepo = new InMemoryNonceRepo();
        CallbackSecurityVerifier verifier = new CallbackSecurityVerifier(SECRET, 300, CLOCK, nonceRepo);

        CallbackMetadata metadata = validMetadata("nonce_shared");

        // 不同租户可以使用相同 nonce
        assertDoesNotThrow(() -> verifier.verify(metadata, "tenant_01", "worker_01", "task_01", "ASR"));
        assertDoesNotThrow(() -> verifier.verify(metadata, "tenant_02", "worker_01", "task_02", "ASR"));
    }

    private CallbackMetadata validMetadata(String nonce) {
        String bodySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // empty body
        String signingString = NOW + "\n" + nonce + "\n" + "PATCH" + "\n" + "/internal/path" + "\n" + bodySha256;
        String signature = "hmac-sha256=" + computeHmac(signingString, SECRET);

        return new CallbackMetadata(
            "worker_01",
            1,
            "worker_01:task_01:1",
            "PATCH",
            "req_001",
            "trace_001",
            NOW,
            nonce,
            "idem_001",
            signature,
            "/internal/path",
            bodySha256
        );
    }

    private String computeHmac(String data, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class InMemoryNonceRepo implements CallbackNonceRepository {
        private final java.util.Set<String> nonces = new java.util.HashSet<>();

        @Override
        public boolean exists(String tenantId, String nonce) {
            return nonces.contains(key(tenantId, nonce));
        }

        @Override
        public boolean record(String tenantId, String nonce, String workerId, String taskId, String stepName) {
            return nonces.add(key(tenantId, nonce));
        }

        @Override
        public int cleanupExpired(OffsetDateTime before) {
            return 0;
        }

        private String key(String tenantId, String nonce) {
            return tenantId + ":" + nonce;
        }
    }
}
