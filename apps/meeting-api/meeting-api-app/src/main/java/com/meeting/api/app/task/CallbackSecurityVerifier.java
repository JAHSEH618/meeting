package com.meeting.api.app.task;

import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.domain.task.CallbackNonceRepository;
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

@Component
public class CallbackSecurityVerifier {
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
     * @throws IllegalArgumentException 验证失败
     */
    public void verify(CallbackMetadata metadata, String tenantId, String workerId, String taskId, String stepName) {
        // 1. HMAC 签名验证
        if (metadata.signature() == null || !metadata.signature().startsWith("hmac-sha256=")) {
            throw new IllegalArgumentException("missing callback signature");
        }
        String expected = "hmac-sha256=" + hmacSha256(signingString(metadata), secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), metadata.signature().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("callback signature mismatch");
        }

        // 2. 时间戳偏移验证
        long skew = Math.abs(Duration.between(metadata.timestamp(), OffsetDateTime.now(clock)).toSeconds());
        if (skew > timestampSkewSeconds) {
            throw new IllegalArgumentException("callback timestamp outside allowed skew");
        }

        // 3. Nonce 去重验证（防止重放攻击）
        if (nonceRepository.exists(tenantId, metadata.nonce())) {
            throw new IllegalArgumentException("callback nonce already used (replay attack detected)");
        }

        // 4. 记录 nonce
        if (!nonceRepository.record(tenantId, metadata.nonce(), workerId, taskId, stepName)) {
            // 并发场景下另一个线程已记录
            throw new IllegalArgumentException("callback nonce already used (replay attack detected)");
        }
    }

    /**
     * 兼容旧签名：不验证 nonce
     * @deprecated 仅用于测试或临时兼容，生产环境应使用 verify(metadata, tenantId, workerId, taskId, stepName)
     */
    @Deprecated
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
