package com.meeting.api;

import com.meeting.api.start.config.ProdProfileValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProdProfileValidatorTest {

    private static final String GOOD_ADMIN_SECRET = "prod-admin-secret-32chars-long!!";

    @Test
    void passesWithFullProdConfig() {
        ProdProfileValidator v = new ProdProfileValidator(
            "prod-callback-secret-32chars-long",
            "prod-aiworker-secret-32chars-long",
            "https://ai-worker.internal.svc:8090",
            "v1.2.0",
            "kms/aws/arn:..::abcd",
            /* adminJwtSecret */ GOOD_ADMIN_SECRET,
            /* flywayBaselineOnMigrate */ false,
            /* authMode */ "jwt",
            /* tenantsActive */ "tenant_acme,tenant_emea"
        );
        assertThat(v.validateInternal()).isEmpty();
    }

    @Test
    void flagsDemoCallbackSecret() {
        ProdProfileValidator v = new ProdProfileValidator(
            "change-me-callback-fallback-secret",
            "real-aiworker-secret",
            "https://ai-worker.internal",
            "v1", "kms-real", GOOD_ADMIN_SECRET, false, "jwt", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("callback.hmac-secret"));
    }

    @Test
    void flagsBlankAiWorkerSecret() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "",
            "https://ai-worker.internal", "v1", "kms-real", GOOD_ADMIN_SECRET,
            false, "jwt", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("ai-worker.hmac-secret"));
    }

    @Test
    void flagsIdenticalSecrets() {
        ProdProfileValidator v = new ProdProfileValidator(
            "same-secret", "same-secret",
            "https://ai-worker.internal", "v1", "kms-real", GOOD_ADMIN_SECRET,
            false, "jwt", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("must not be identical"));
    }

    @Test
    void flagsLocalhostAiWorkerBaseUrl() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "http://localhost:8090", "v1", "kms-real", GOOD_ADMIN_SECRET,
            false, "jwt", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("ai-worker.base-url"));
    }

    @Test
    void flagsDevKmsMasterKey() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "dev-kms-master-key", GOOD_ADMIN_SECRET,
            false, "jwt", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("kms.master-key-id"));
    }

    @Test
    void flagsDemoAdminJwtSecret() {
        // The default secret shipped in application.yml is publicly known; if
        // ops forget to override AI_WORKER_ADMIN_JWT_SECRET, anyone can forge
        // ADMIN tokens for the ai-worker admin API. Prod boot must reject it.
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "kms-real",
            "dev-admin-secret-32-bytes-fixedXX",
            false, "jwt", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("admin-jwt.secret"));
    }

    @Test
    void flagsFlywayBaselineOnMigrate() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "kms-real", GOOD_ADMIN_SECRET,
            /* baseline-on-migrate */ true, "jwt", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("baseline-on-migrate"));
    }

    @Test
    void flagsInMemoryAuthMode() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "kms-real", GOOD_ADMIN_SECRET,
            false, "in-memory", "tenant_acme"
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("meeting.auth.mode"));
    }

    @Test
    void flagsMissingActiveTenants() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "kms-real", GOOD_ADMIN_SECRET,
            false, "jwt", ""
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("meeting.tenants.active"));
    }

    @Test
    void flagsCommaOnlyActiveTenants() {
        // "," and " , " previously passed isBlank() but parsed to an
        // empty list at the schedulers — they silently processed zero
        // tenants. Validator must reject any raw value whose parsed
        // ActiveTenantList is empty.
        for (String raw : new String[] {",", " , ", ",,", " , , ,"}) {
            ProdProfileValidator v = new ProdProfileValidator(
                "real-callback", "real-aiworker",
                "https://ai-worker.internal", "v1", "kms-real", GOOD_ADMIN_SECRET,
                false, "jwt", raw
            );
            assertThat(v.validateInternal())
                .as("raw value %s should be rejected", raw)
                .anySatisfy(s -> assertThat(s).contains("meeting.tenants.active"));
        }
    }

    @Test
    void aggregatesAllFailures() {
        ProdProfileValidator v = new ProdProfileValidator(
            "", "", "http://127.0.0.1:8090", "", "", "",
            true, "in-memory", ""
        );
        assertThat(v.validateInternal())
            .hasSizeGreaterThanOrEqualTo(9);
    }
}
