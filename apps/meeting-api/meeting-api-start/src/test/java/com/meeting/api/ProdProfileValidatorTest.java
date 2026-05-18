package com.meeting.api;

import com.meeting.api.start.config.ProdProfileValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProdProfileValidatorTest {

    @Test
    void passesWithFullProdConfig() {
        ProdProfileValidator v = new ProdProfileValidator(
            "prod-callback-secret-32chars-long",
            "prod-aiworker-secret-32chars-long",
            "https://ai-worker.internal.svc:8090",
            "v1.2.0",
            "kms/aws/arn:..::abcd",
            /* llmAllowConfidential */ false,
            /* flywayBaselineOnMigrate */ false
        );
        assertThat(v.validateInternal()).isEmpty();
    }

    @Test
    void flagsDemoCallbackSecret() {
        ProdProfileValidator v = new ProdProfileValidator(
            "change-me-callback-fallback-secret",
            "real-aiworker-secret",
            "https://ai-worker.internal",
            "v1", "kms-real", false, false
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("callback.hmac-secret"));
    }

    @Test
    void flagsBlankAiWorkerSecret() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "",
            "https://ai-worker.internal", "v1", "kms-real",
            false, false
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("ai-worker.hmac-secret"));
    }

    @Test
    void flagsIdenticalSecrets() {
        ProdProfileValidator v = new ProdProfileValidator(
            "same-secret", "same-secret",
            "https://ai-worker.internal", "v1", "kms-real",
            false, false
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("must not be identical"));
    }

    @Test
    void flagsLocalhostAiWorkerBaseUrl() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "http://localhost:8090", "v1", "kms-real",
            false, false
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("ai-worker.base-url"));
    }

    @Test
    void flagsDevKmsMasterKey() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "dev-kms-master-key",
            false, false
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("kms.master-key-id"));
    }

    @Test
    void flagsLlmAllowConfidential() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "kms-real",
            /* allow confidential */ true, false
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("allow-confidential"));
    }

    @Test
    void flagsFlywayBaselineOnMigrate() {
        ProdProfileValidator v = new ProdProfileValidator(
            "real-callback", "real-aiworker",
            "https://ai-worker.internal", "v1", "kms-real",
            false, /* baseline-on-migrate */ true
        );
        assertThat(v.validateInternal())
            .anySatisfy(s -> assertThat(s).contains("baseline-on-migrate"));
    }

    @Test
    void aggregatesAllFailures() {
        ProdProfileValidator v = new ProdProfileValidator(
            "", "", "http://127.0.0.1:8090", "", "",
            true, true
        );
        assertThat(v.validateInternal())
            .hasSizeGreaterThanOrEqualTo(6);
    }
}
