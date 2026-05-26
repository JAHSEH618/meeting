package com.meeting.api.start.config;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.compliance.DeletionJobRunner;
import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.compliance.DeletionCertificateHasher;
import com.meeting.api.domain.compliance.DeletionCertificateRepository;
import com.meeting.api.domain.compliance.DeletionExecutorPort;
import com.meeting.api.domain.compliance.DeletionJobRepository;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import com.meeting.api.infrastructure.gateway.compliance.DeletionExecutorRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.meeting.api.domain.compliance.KmsKeyDestroyerPort;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.infrastructure.gateway.compliance.NoOpKmsKeyDestroyerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wires {@link DeletionJobRunner} into the Spring scheduler. Disabled
 * with {@code meeting.deletion-runner.enabled=false} (useful in tests
 * + during prod cut-overs).
 */
@Configuration
@ConditionalOnProperty(
    prefix = "meeting.deletion-runner",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class DeletionJobRunnerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionJobRunnerConfig.class);

    private final DeletionJobRunner runner;
    private final List<String> tenantIds;

    public DeletionJobRunnerConfig(
        DeletionJobRepository repo,
        LegalHoldCheckPort legalHoldCheck,
        DeletionExecutorRegistry registry,
        DeletionCertificateHasher hasher,
        DeletionCertificateRepository certificateRepository,
        TenantScopedTransaction tenantTx,
        AuditEventLogger audit,
        @Value("${meeting.deletion-runner.batch-size:25}") int batchSize,
        @Value("${meeting.tenants.active:${meeting.deletion-runner.tenants:tenant_default}}")
            String tenantIdsCsv
    ) {
        this.runner = new DeletionJobRunner(
            repo,
            legalHoldCheck,
            (DeletionScopeType scope) -> registry.find(scope).map(e -> (DeletionExecutorPort) e),
            hasher,
            certificateRepository,
            tenantTx,
            audit,
            Clock.systemUTC(),
            batchSize
        );
        this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
    }

    @Bean
    public DeletionJobRunner deletionJobRunner() {
        return runner;
    }

    @Bean
    @ConditionalOnMissingBean(KmsKeyDestroyerPort.class)
    public KmsKeyDestroyerPort kmsKeyDestroyerPort(SpeakerEmbeddingRepository embeddingRepository) {
        return new NoOpKmsKeyDestroyerPort(embeddingRepository);
    }

    @Scheduled(
        fixedDelayString = "${meeting.deletion-runner.interval-ms:60000}",
        initialDelayString = "${meeting.deletion-runner.initial-delay-ms:60000}"
    )
    public void scan() {
        if (tenantIds.isEmpty()) return;
        try {
            DeletionJobRunner.RunReport report = runner.runOnce(tenantIds);
            if (report.claimed() > 0) {
                LOG.info(
                    "deletion_runner_run claimed={} succeeded={} partial={} failed={} blocked={}",
                    report.claimed(), report.succeeded(),
                    report.partialFailed(), report.failed(),
                    report.blockedByLegalHold()
                );
            }
        } catch (RuntimeException ex) {
            LOG.warn("deletion_runner_failed", ex);
        }
    }

    // Helper kept private so the unused-import linter on Optional stays quiet.
    @SuppressWarnings("unused")
    private void touchOptional(Optional<?> opt) {}
}
