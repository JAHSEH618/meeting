package com.meeting.api.start.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 8.1.3 — fail-fast on prod boot when required secrets / configs are
 * missing, still set to dev demo values, or in obviously unsafe states
 * (e.g. LLM allowed on CONFIDENTIAL, Flyway baseline-on-migrate true).
 *
 * <p>Activates only with the {@code prod} Spring profile. Throws a
 * {@link BeanCreationException} listing every violation so ops see all
 * problems in one shot instead of fixing them one boot at a time.
 */
@Component
@Profile("prod")
public class ProdProfileValidator {

    private static final List<String> KNOWN_DEMO_VALUES = List.of(
        "change-me-callback-fallback-secret",
        "change-me-internal-fallback-secret",
        "dev-kms-master-key",
        "demo", "changeme", "secret", ""
    );

    private final String callbackHmac;
    private final String aiWorkerHmac;
    private final String aiWorkerBaseUrl;
    private final String chunkStrategyVersion;
    private final String kmsMasterKeyId;
    private final boolean llmAllowConfidential;
    private final boolean flywayBaselineOnMigrate;
    private final String authMode;
    private final String tenantsActive;

    public ProdProfileValidator(
        @Value("${meeting.security.callback.hmac-secret:}") String callbackHmac,
        @Value("${meeting.security.ai-worker.hmac-secret:}") String aiWorkerHmac,
        @Value("${meeting.security.ai-worker.base-url:}") String aiWorkerBaseUrl,
        @Value("${meeting.chunk.strategy-version:}") String chunkStrategyVersion,
        @Value("${meeting.kms.master-key-id:}") String kmsMasterKeyId,
        @Value("${meeting.llm.allow-confidential:false}") boolean llmAllowConfidential,
        @Value("${spring.flyway.baseline-on-migrate:false}") boolean flywayBaselineOnMigrate,
        @Value("${meeting.auth.mode:in-memory}") String authMode,
        @Value("${meeting.tenants.active:}") String tenantsActive
    ) {
        this.callbackHmac = callbackHmac;
        this.aiWorkerHmac = aiWorkerHmac;
        this.aiWorkerBaseUrl = aiWorkerBaseUrl;
        this.chunkStrategyVersion = chunkStrategyVersion;
        this.kmsMasterKeyId = kmsMasterKeyId;
        this.llmAllowConfidential = llmAllowConfidential;
        this.flywayBaselineOnMigrate = flywayBaselineOnMigrate;
        this.authMode = authMode;
        this.tenantsActive = tenantsActive;
    }

    @PostConstruct
    public void validate() {
        List<String> failures = validateInternal();
        if (failures.isEmpty()) {
            return;
        }
        throw new BeanCreationException(
            "prod profile configuration is unsafe:\n  - "
                + String.join("\n  - ", failures)
        );
    }

    /** Visible for tests — runs the rules without throwing. */
    public List<String> validateInternal() {
        List<String> failures = new ArrayList<>();
        if (isBlankOrDemo(callbackHmac)) {
            failures.add(
                "meeting.security.callback.hmac-secret must be set to a non-demo value (was '"
                    + mask(callbackHmac) + "')"
            );
        }
        if (isBlankOrDemo(aiWorkerHmac)) {
            failures.add(
                "meeting.security.ai-worker.hmac-secret must be set to a non-demo value (was '"
                    + mask(aiWorkerHmac) + "')"
            );
        }
        if (!isBlankOrDemo(callbackHmac)
            && !isBlankOrDemo(aiWorkerHmac)
            && callbackHmac.equals(aiWorkerHmac)) {
            failures.add(
                "meeting.security.callback.hmac-secret and meeting.security.ai-worker.hmac-secret must not be identical"
            );
        }
        if (isBlank(aiWorkerBaseUrl)
            || aiWorkerBaseUrl.contains("localhost")
            || aiWorkerBaseUrl.contains("127.0.0.1")) {
            failures.add(
                "meeting.security.ai-worker.base-url must point at a non-localhost host (was '"
                    + aiWorkerBaseUrl + "')"
            );
        }
        if (isBlank(chunkStrategyVersion)) {
            failures.add("meeting.chunk.strategy-version must be set in prod");
        }
        if (isBlankOrDemo(kmsMasterKeyId)) {
            failures.add(
                "meeting.kms.master-key-id must be set to a non-demo KMS key id (was '"
                    + mask(kmsMasterKeyId) + "')"
            );
        }
        if (llmAllowConfidential) {
            failures.add(
                "meeting.llm.allow-confidential must be false — CONFIDENTIAL/SECRET meetings must remain fail-closed"
            );
        }
        if (flywayBaselineOnMigrate) {
            failures.add(
                "spring.flyway.baseline-on-migrate must be false in prod — silent baselining hides schema drift"
            );
        }
        if ("in-memory".equalsIgnoreCase(authMode)) {
            failures.add(
                "meeting.auth.mode must NOT be 'in-memory' in prod — InMemoryAuthApplicationService"
                    + " ships hardcoded admin/admin123 / tenant_default credentials and is dev-only"
            );
        }
        // Parsed list rather than raw isBlank() — "," and " , " used to
        // sneak through here but produced an empty list at every
        // scheduler (see ActiveTenantList), so background work silently
        // no-op'd in prod.
        if (ActiveTenantList.parse(tenantsActive).isEmpty()) {
            failures.add(
                "meeting.tenants.active must list at least one tenant id in prod — schedulers"
                    + " (outbox publisher / lease scanner / deletion runner / break-glass scanner)"
                    + " silently process zero tenants otherwise (raw value: '"
                    + (tenantsActive == null ? "" : tenantsActive) + "')"
            );
        }
        return failures;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isBlankOrDemo(String value) {
        if (isBlank(value)) {
            return true;
        }
        String trimmed = value.trim();
        return KNOWN_DEMO_VALUES.contains(trimmed)
            || trimmed.toLowerCase().startsWith("change-me")
            || trimmed.toLowerCase().contains("fallback");
    }

    /** Mask a secret-like value for log output, keeping the last 4 chars. */
    private static String mask(String value) {
        if (value == null) return "<null>";
        if (value.length() <= 4) return "***" + value;
        return "***" + value.substring(value.length() - 4);
    }
}
