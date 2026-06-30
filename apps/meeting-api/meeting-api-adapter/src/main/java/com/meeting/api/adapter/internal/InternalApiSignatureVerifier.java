package com.meeting.api.adapter.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies HMAC signatures on inbound worker → Java internal-API calls that use
 * the {@code meeting.ai-worker.hmac-secret} (the same secret Java uses outbound
 * for {@code /internal/rerank}). Mirrors the worker's signing-string format:
 *
 * <pre>
 *   timestamp \n nonce \n METHOD \n PATH_WITH_QUERY \n SHA256(body).hex
 * </pre>
 *
 * Signature header value: {@code hmac-sha256=<hex>}.
 *
 * <p><strong>Replay protection — NONE.</strong> The {@code X-Nonce} header is folded
 * into the signing string only to <em>bind</em> the nonce to the signature; this
 * verifier does <em>not</em> consult a seen-nonce store, so a captured request can be
 * replayed verbatim within the timestamp-skew window. Anti-replay here relies solely on
 * timestamp-skew bounding + TLS, NOT on nonce uniqueness. This is acceptable today only
 * because every endpoint guarded by this verifier is READ-ONLY (speaker-reference
 * centroid fetch): a replay merely re-discloses an already-authorized centroid.
 *
 * <p><strong>If any endpoint sharing this verifier ever becomes state-mutating, a
 * bounded nonce-replay cache (TTL = the timestamp-skew window) MUST be added here</strong>
 * before that endpoint ships, so a captured request cannot be replayed to repeat the
 * mutation.
 */
@Component
public class InternalApiSignatureVerifier {
    private final String secret;
    private final long timestampSkewSeconds;
    private final Clock clock;

    @Autowired
    public InternalApiSignatureVerifier(
        @Value("${meeting.security.internal-api.hmac-secret:${meeting.ai-worker.hmac-secret:change-me-internal-fallback}}") String secret,
        @Value("${meeting.security.internal-api.timestamp-skew-seconds:300}") long timestampSkewSeconds
    ) {
        this(secret, timestampSkewSeconds, Clock.systemUTC());
    }
    public InternalApiSignatureVerifier(String secret, long timestampSkewSeconds, Clock clock) {
        this.secret = secret;
        this.timestampSkewSeconds = timestampSkewSeconds;
        this.clock = clock;
    }
    /**
     * Verifies the HMAC signature, timestamp skew, and body hash. The {@code nonce}
     * is signature-binding only — see the class Javadoc: this method performs NO
     * replay check, so callers must keep the guarded endpoints read-only (or add a
     * nonce-replay cache) before relying on it.
     */
    public void verify(
        String method,
        String urlPathWithQuery,
        byte[] body,
        String timestamp,
        String nonce,
        String signature
    ) {
        if (signature == null || !signature.startsWith("hmac-sha256=")) {
            throw new IllegalArgumentException("missing or malformed signature header");
        }
        OffsetDateTime ts;
        try {
            ts = OffsetDateTime.parse(timestamp);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid X-Timestamp: " + timestamp);
        }
        long skew = Math.abs(Duration.between(ts, OffsetDateTime.now(clock)).toSeconds());
        if (skew > timestampSkewSeconds) {
            throw new IllegalArgumentException("timestamp outside allowed skew");
        }
        String bodyHash = sha256Hex(body == null ? new byte[0] : body);
        String signingString = timestamp + "\n" + nonce + "\n" + method + "\n" + urlPathWithQuery + "\n" + bodyHash;
        String expected = "hmac-sha256=" + hmacSha256Hex(signingString, secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("signature mismatch");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmacSha256Hex(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to compute HMAC", ex);
        }
    }
}
