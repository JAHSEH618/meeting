package com.meeting.api.app.task;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.domain.task.CallbackNonceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CallbackSecurityVerifier {
    // Canonical timestamp formatter for the HMAC signing string. This MUST stay byte-for-byte
    // identical to what the Python ai-worker signs over (strftime("%Y-%m-%dT%H:%M:%SZ"), which
    // ALWAYS emits the seconds field) and to HttpAiWorkerInternalClient.ISO_INSTANT_SECONDS.
    // We cannot rely on OffsetDateTime.toString() here: when both seconds and nanos are zero it
    // OMITS the seconds field (e.g. "2026-06-30T12:00Z" instead of "2026-06-30T12:00:00Z"), so
    // ~1/60 of callbacks would produce a different canonical string than Python signed and the
    // HMAC would spuriously mismatch.
    private static final DateTimeFormatter ISO_INSTANT_SECONDS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final String secret;
    private final long timestampSkewSeconds;
    private final Clock clock;
    private final CallbackNonceRepository nonceRepository;

    @Autowired
    public CallbackSecurityVerifier(
        @Value("${meeting.security.callback.hmac-secret:change-me-callback-fallback-secret}") String secret,
        @Value("${meeting.security.callback.timestamp-skew-seconds:300}") long timestampSkewSeconds,
        CallbackNonceRepository nonceRepository
    ) {
        this(secret, timestampSkewSeconds, Clock.systemUTC(), nonceRepository);
    }
    public CallbackSecurityVerifier(String secret, long timestampSkewSeconds, Clock clock, CallbackNonceRepository nonceRepository) {
        this.secret = secret;
        this.timestampSkewSeconds = timestampSkewSeconds;
        this.clock = clock;
        this.nonceRepository = nonceRepository;
    }
    /**
     * 验证回调签名、时间戳和 nonce
     *
     * @param metadata 回调元数据
     * @param tenantId 租户ID
     * @param workerId Worker ID
     * @param taskId 任务ID (可选)
     * @param stepName 步骤名称 (可选)
     * @throws ApplicationException 验证失败 (HTTP 401 CALLBACK_AUTH_FAILED). 仅时间戳偏移失败可重试
     *     (retryable=true)，签名/nonce 失败不可重试 (retryable=false)。
     */
    public void verify(CallbackMetadata metadata, String tenantId, String workerId, String taskId, String stepName) {
        // 1. HMAC 签名验证
        if (metadata.signature() == null || !metadata.signature().startsWith("hmac-sha256=")) {
            throw authFailed("missing callback signature", false);
        }
        String expected = "hmac-sha256=" + hmacSha256(signingString(metadata), secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), metadata.signature().getBytes(StandardCharsets.UTF_8))) {
            throw authFailed("callback signature mismatch", false);
        }

        // 2. 时间戳偏移验证 (transient clock skew — a retry with a fresh timestamp can succeed)
        long skew = Math.abs(Duration.between(metadata.timestamp(), OffsetDateTime.now(clock)).toSeconds());
        if (skew > timestampSkewSeconds) {
            throw authFailed("callback timestamp outside allowed skew", true);
        }

        // 3. Nonce 去重验证（防止重放攻击）
        if (nonceRepository.exists(tenantId, metadata.nonce())) {
            throw authFailed("callback nonce already used (replay attack detected)", false);
        }

        // 4. 记录 nonce
        if (!nonceRepository.record(tenantId, metadata.nonce(), workerId, taskId, stepName)) {
            // 并发场景下另一个线程已记录
            throw authFailed("callback nonce already used (replay attack detected)", false);
        }
    }

    private static ApplicationException authFailed(String message, boolean retryable) {
        return new ApplicationException(ErrorCode.CALLBACK_AUTH_FAILED, 401, message, retryable);
    }

    /**
     * 兼容旧签名：不验证 nonce
     * @deprecated 仅用于测试或临时兼容，生产环境应使用 verify(metadata, tenantId, workerId, taskId, stepName)
     */
    @Deprecated
    public void verify(CallbackMetadata metadata) {
        if (metadata.signature() == null || !metadata.signature().startsWith("hmac-sha256=")) {
            throw authFailed("missing callback signature", false);
        }
        long skew = Math.abs(Duration.between(metadata.timestamp(), OffsetDateTime.now(clock)).toSeconds());
        if (skew > timestampSkewSeconds) {
            throw authFailed("callback timestamp outside allowed skew", true);
        }
        String expected = "hmac-sha256=" + hmacSha256(signingString(metadata), secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), metadata.signature().getBytes(StandardCharsets.UTF_8))) {
            throw authFailed("callback signature mismatch", false);
        }
    }

    private static String signingString(CallbackMetadata metadata) {
        // Format the timestamp with the fixed canonical formatter (UTC, seconds always present)
        // rather than OffsetDateTime.toString(), which drops the seconds segment when seconds and
        // nanos are both zero and would diverge from what Python signed. Convert to UTC first
        // defensively so the offset is rendered as the literal "Z" suffix in every case.
        return ISO_INSTANT_SECONDS.format(metadata.timestamp().withOffsetSameInstant(ZoneOffset.UTC)) + "\n"
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
