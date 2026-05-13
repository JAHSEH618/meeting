package com.meeting.api.app.task;

import com.meeting.api.client.internal.callback.CallbackMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CallbackSecurityVerifier {
    private final String secret;
    private final long timestampSkewSeconds;
    private final Clock clock;

    public CallbackSecurityVerifier(
        @Value("${meeting.security.callback.hmac-secret:change-me-callback-fallback-secret}") String secret,
        @Value("${meeting.security.callback.timestamp-skew-seconds:300}") long timestampSkewSeconds
    ) {
        this(secret, timestampSkewSeconds, Clock.systemUTC());
    }

    public CallbackSecurityVerifier(String secret, long timestampSkewSeconds, Clock clock) {
        this.secret = secret;
        this.timestampSkewSeconds = timestampSkewSeconds;
        this.clock = clock;
    }

    public void verify(CallbackMetadata metadata) {
        if (metadata.signature() == null || !metadata.signature().startsWith("hmac-sha256=")) {
            throw new IllegalArgumentException("missing callback signature");
        }
        long skew = Math.abs(Duration.between(metadata.timestamp(), OffsetDateTime.now(clock)).toSeconds());
        if (skew > timestampSkewSeconds) {
            throw new IllegalArgumentException("callback timestamp outside allowed skew");
        }
        String expected = "hmac-sha256=" + hmacSha256(signingString(metadata), secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), metadata.signature().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("callback signature mismatch");
        }
    }

    private static String signingString(CallbackMetadata metadata) {
        return metadata.timestamp() + "\n"
            + metadata.nonce() + "\n"
            + metadata.httpMethod() + "\n"
            + metadata.urlPathWithQuery() + "\n"
            + metadata.bodySha256();
    }

    private static String hmacSha256(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute callback hmac", e);
        }
    }
}
