package com.meeting.api;

import com.meeting.api.adapter.internal.InternalApiSignatureVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workstation D7 — InternalApiSignatureVerifier unit tests (covers B5.7's
 * "HMAC 正确 / 错签名 / 时间戳偏移 / nonce 重放" axes; replay is enforced at the
 * worker-side LRU, not on the Java side, so the replay test is moved there.)
 */
class InternalApiSignatureVerifierTest {
    private static final String SECRET = "test-secret-with-enough-entropy-for-hs256";
    private static final OffsetDateTime FIXED = OffsetDateTime.parse("2026-05-19T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-19T06:00:00Z"), ZoneOffset.UTC);
    private static final InternalApiSignatureVerifier VERIFIER = new InternalApiSignatureVerifier(SECRET, 300, CLOCK);

    @Test
    void verifiesCorrectlySignedRequest() {
        byte[] body = "{\"tenantId\":\"t\",\"personIds\":[\"p\"]}".getBytes(StandardCharsets.UTF_8);
        String timestamp = FIXED.toString();
        String nonce = "nonce-abc";
        String signature = sign("POST", "/internal/speakers/reference-embeddings", body, timestamp, nonce, SECRET);
        VERIFIER.verify("POST", "/internal/speakers/reference-embeddings", body, timestamp, nonce, signature);
        // no throw = pass
    }

    @Test
    void rejectsForgedSignature() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() ->
            VERIFIER.verify("POST", "/internal/speakers/reference-embeddings", body, FIXED.toString(), "n", "hmac-sha256=" + "0".repeat(64))
        ).hasMessageContaining("signature mismatch");
    }

    @Test
    void rejectsTimestampOutsideSkew() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String farPast = FIXED.minusSeconds(1_000).toString();
        String nonce = "n";
        String signature = sign("POST", "/internal/speakers/reference-embeddings", body, farPast, nonce, SECRET);
        assertThatThrownBy(() ->
            VERIFIER.verify("POST", "/internal/speakers/reference-embeddings", body, farPast, nonce, signature)
        ).hasMessageContaining("skew");
    }

    @Test
    void rejectsMalformedSignatureHeader() {
        assertThatThrownBy(() ->
            VERIFIER.verify("POST", "/internal/x", new byte[0], FIXED.toString(), "n", "bearer foo")
        ).hasMessageContaining("malformed");
    }

    @Test
    void rejectsInvalidTimestamp() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = sign("POST", "/x", body, "not-a-date", "n", SECRET);
        assertThatThrownBy(() ->
            VERIFIER.verify("POST", "/x", body, "not-a-date", "n", signature)
        ).hasMessageContaining("invalid X-Timestamp");
    }

    private static String sign(String method, String path, byte[] body, String timestamp, String nonce, String secret) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String signingString = timestamp + "\n" + nonce + "\n" + method + "\n" + path + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "hmac-sha256=" + HexFormat.of().formatHex(mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
