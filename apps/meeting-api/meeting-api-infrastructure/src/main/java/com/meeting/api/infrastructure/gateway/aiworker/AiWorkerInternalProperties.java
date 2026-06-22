package com.meeting.api.infrastructure.gateway.aiworker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code meeting.security.ai-worker.*} in application.yml.
 *
 * <p>{@code hmacSecret} signs every outbound request to ai-worker's
 * internal API. It MUST differ from {@code meeting.security.callback.hmac-secret}
 * (the inbound callback verifier) — see CLAUDE.md HMAC notes.
 *
 * <p>Components are {@link Integer} so the compact constructor can supply
 * defaults when Spring binds them as {@code null}. Callers accept
 * autoboxed {@code Integer} returns.
 */
@ConfigurationProperties(prefix = "meeting.security.ai-worker")
public record AiWorkerInternalProperties(
    String baseUrl,
    String hmacSecret,
    Integer rerankTimeoutMs,
    Integer embedTimeoutMs,
    Integer warmupTimeoutMs,
    Integer modelsTimeoutMs,
    Integer connectTimeoutMs
) {
    public AiWorkerInternalProperties {
        baseUrl = stripTrailingSlash(baseUrl);
        if (rerankTimeoutMs == null || rerankTimeoutMs <= 0) {
            rerankTimeoutMs = 3000;
        }
        if (embedTimeoutMs == null || embedTimeoutMs <= 0) {
            embedTimeoutMs = 3000;
        }
        if (warmupTimeoutMs == null || warmupTimeoutMs <= 0) {
            warmupTimeoutMs = 5000;
        }
        if (modelsTimeoutMs == null || modelsTimeoutMs <= 0) {
            modelsTimeoutMs = 2000;
        }
        if (connectTimeoutMs == null || connectTimeoutMs <= 0) {
            connectTimeoutMs = 1000;
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value;
        while (stripped.endsWith("/") && !stripped.endsWith("://")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
