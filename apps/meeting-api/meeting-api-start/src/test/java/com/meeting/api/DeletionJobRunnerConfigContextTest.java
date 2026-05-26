package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.compliance.DeletionJobRunner;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.compliance.DeletionCertificateHasher;
import com.meeting.api.domain.compliance.DeletionCertificateRepository;
import com.meeting.api.domain.compliance.DeletionJob;
import com.meeting.api.domain.compliance.DeletionJobRepository;
import com.meeting.api.domain.compliance.KmsKeyDestroyerPort;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.infrastructure.gateway.compliance.DeletionExecutorRegistry;
import com.meeting.api.infrastructure.gateway.compliance.SpeakerProfileDeletionExecutor;
import com.meeting.api.start.config.DeletionJobRunnerConfig;
import com.meeting.api.start.config.KmsKeyDestroyerConfig;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionJobRunnerConfigContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(DeletionRunnerSlice.class)
        .withPropertyValues(
            "meeting.deletion-runner.enabled=true",
            "meeting.tenants.active=tenant_01"
        );

    @Test
    void wiresDefaultKmsDestroyerWithoutCyclingThroughDeletionRunnerConfig() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DeletionJobRunner.class);
            assertThat(context).hasSingleBean(DeletionExecutorRegistry.class);
            assertThat(context).hasSingleBean(KmsKeyDestroyerPort.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        KmsKeyDestroyerConfig.class,
        DeletionJobRunnerConfig.class,
        DeletionExecutorRegistry.class,
        SpeakerProfileDeletionExecutor.class
    })
    static class DeletionRunnerSlice {

        @Bean
        DeletionJobRepository deletionJobRepository() {
            return new DeletionJobRepository() {
                @Override public void save(DeletionJob job) {}
                @Override public void update(DeletionJob job) {}
                @Override public Optional<DeletionJob> findById(String tenantId, String jobId) {
                    return Optional.empty();
                }
                @Override
                public PageResult<DeletionJob> listByTenant(String tenantId, String cursor, int limit) {
                    return new PageResult<>(List.of(), new PageResult.PageInfo(null, false, limit));
                }
                @Override
                public List<DeletionJob> claimByStatus(
                    String tenantId, DeletionJobStatus status, int limit
                ) {
                    return List.of();
                }
            };
        }

        @Bean
        LegalHoldCheckPort legalHoldCheckPort() {
            return (tenantId, scopeType, scopeId) -> false;
        }

        @Bean
        DeletionCertificateHasher deletionCertificateHasher() {
            return (tenantId, jobId, outcome) -> "sha256:test";
        }

        @Bean
        DeletionCertificateRepository deletionCertificateRepository() {
            return new DeletionCertificateRepository() {
                private final List<DeletionCertificateRecord> saved = new ArrayList<>();

                @Override
                public void save(DeletionCertificateRecord record) {
                    saved.add(record);
                }

                @Override
                public Optional<DeletionCertificateRecord> findByJobId(
                    String tenantId, String deletionJobId
                ) {
                    return saved.stream()
                        .filter(record -> tenantId.equals(record.tenantId()))
                        .filter(record -> deletionJobId.equals(record.deletionJobId()))
                        .findFirst();
                }
            };
        }

        @Bean
        TenantScopedTransaction tenantScopedTransaction() {
            return TenantScopedTransaction.immediate();
        }

        @Bean
        AuditEventLogger auditEventLogger() {
            return entry -> {};
        }

        @Bean
        SpeakerProfileRepository speakerProfileRepository() {
            return new SpeakerProfileRepository() {
                @Override public SpeakerProfile save(SpeakerProfile profile) { return profile; }
                @Override public Optional<SpeakerProfile> findById(String tenantId, String profileId) {
                    return Optional.empty();
                }
                @Override public List<SpeakerProfile> listByTenant(
                    String tenantId, boolean includeRevoked
                ) {
                    return List.of();
                }
                @Override public List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds) {
                    return List.of();
                }
                @Override
                public void updateConsentStatus(
                    String tenantId,
                    String profileId,
                    String consentStatus,
                    OffsetDateTime revokedAt,
                    OffsetDateTime deletedAt,
                    OffsetDateTime updatedAt
                ) {}
            };
        }

        @Bean
        SpeakerEmbeddingRepository speakerEmbeddingRepository() {
            return new SpeakerEmbeddingRepository() {
                @Override public void save(SpeakerEmbeddingRecord record) {}
                @Override public List<SpeakerEmbeddingRecord> findByProfile(
                    String tenantId, String speakerProfileId
                ) {
                    return List.of();
                }
                @Override public int revokeForProfile(
                    String tenantId, String speakerProfileId, OffsetDateTime now
                ) {
                    return 0;
                }
                @Override public int deleteForProfile(
                    String tenantId, String speakerProfileId, OffsetDateTime now
                ) {
                    return 0;
                }
            };
        }
    }
}
