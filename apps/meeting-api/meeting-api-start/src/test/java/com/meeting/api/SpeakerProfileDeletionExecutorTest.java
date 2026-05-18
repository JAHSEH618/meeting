package com.meeting.api;

import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionExecutorPort.DeletionOutcome;
import com.meeting.api.domain.compliance.KmsKeyDestroyerPort;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.infrastructure.gateway.compliance.SpeakerProfileDeletionExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerProfileDeletionExecutorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T12:00:00Z");

    private InMemoryProfileRepo profileRepo;
    private InMemoryEmbeddingRepo embeddingRepo;
    private RecordingDestroyer destroyer;
    private SpeakerProfileDeletionExecutor executor;

    @BeforeEach
    void setUp() {
        profileRepo = new InMemoryProfileRepo();
        embeddingRepo = new InMemoryEmbeddingRepo();
        destroyer = new RecordingDestroyer();
        executor = new SpeakerProfileDeletionExecutor(
            profileRepo, embeddingRepo, destroyer,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    @Test
    void supportsSpeakerProfileScope() {
        assertThat(executor.supportedScope()).isEqualTo(DeletionScopeType.SPEAKER_PROFILE);
    }

    @Test
    void fullSuccessTransitionsProfileAndDestroysKeys() {
        profileRepo.rows.put("sp_01", profile("sp_01", "ACTIVE"));
        embeddingRepo.deletedCount.put("sp_01", 3);
        destroyer.keys.put("sp_01", List.of("key_a", "key_b", "key_c"));

        DeletionOutcome outcome = executor.execute("tenant_01", "sp_01", "deletion-runner");

        assertThat(outcome.deletedRows()).containsEntry("speaker_profiles", 1);
        assertThat(outcome.deletedRows()).containsEntry("speaker_embeddings", 3);
        assertThat(outcome.kmsKeysDestroyed()).containsEntry("speaker_dek_count", 3);
        assertThat(outcome.kmsKeysDestroyed()).containsKey("speaker_dek_ids");
        assertThat(outcome.failedItems()).isEmpty();
        // Profile's consent_status transition happened
        assertThat(profileRepo.consentTransitions).hasSize(1);
        assertThat(profileRepo.consentTransitions.get(0).newStatus()).isEqualTo("DELETED");
    }

    @Test
    void recordsFailureWhenProfileMissing() {
        DeletionOutcome outcome = executor.execute("tenant_01", "sp_missing", "deletion-runner");

        assertThat(outcome.failedItems()).containsExactly("speaker_profile:sp_missing:not_found");
        assertThat(outcome.deletedRows()).isEmpty();
        assertThat(destroyer.calls).isEmpty();
    }

    @Test
    void kmsDestroyExceptionStillSoftDeletesProfile() {
        profileRepo.rows.put("sp_kms_fail", profile("sp_kms_fail", "ACTIVE"));
        embeddingRepo.deletedCount.put("sp_kms_fail", 1);
        destroyer.throwOnCall = new RuntimeException("vault offline");

        DeletionOutcome outcome = executor.execute("tenant_01", "sp_kms_fail", "deletion-runner");

        assertThat(outcome.failedItems()).anyMatch(s -> s.contains("kms:sp_kms_fail"));
        // Profile + embeddings still cascaded so the operator can re-run.
        assertThat(outcome.deletedRows()).containsEntry("speaker_profiles", 1);
        assertThat(outcome.deletedRows()).containsEntry("speaker_embeddings", 1);
    }

    @Test
    void zeroEmbeddingsAndZeroKeysOmitsCounters() {
        profileRepo.rows.put("sp_empty", profile("sp_empty", "ACTIVE"));

        DeletionOutcome outcome = executor.execute("tenant_01", "sp_empty", "deletion-runner");

        assertThat(outcome.deletedRows()).containsEntry("speaker_profiles", 1);
        assertThat(outcome.deletedRows()).doesNotContainKey("speaker_embeddings");
        assertThat(outcome.kmsKeysDestroyed()).isEmpty();
    }

    private static SpeakerProfile profile(String id, String consent) {
        return SpeakerProfile.restore(
            id, "tenant_01", "person_" + id, "Display",
            consent, "manual", "v1", "user_admin",
            null, null, NOW.minusDays(1), NOW.minusHours(1)
        );
    }

    private static class InMemoryProfileRepo implements SpeakerProfileRepository {
        final Map<String, SpeakerProfile> rows = new HashMap<>();
        final List<ConsentTransition> consentTransitions = new ArrayList<>();

        @Override public SpeakerProfile save(SpeakerProfile profile) {
            rows.put(profile.id(), profile);
            return profile;
        }

        @Override public Optional<SpeakerProfile> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) {
            return new ArrayList<>(rows.values());
        }

        @Override public List<SpeakerProfile> findByIds(String tenantId, List<String> ids) {
            return new ArrayList<>();
        }

        @Override
        public void updateConsentStatus(
            String tenantId, String profileId, String consentStatus,
            OffsetDateTime revokedAt, OffsetDateTime deletedAt, OffsetDateTime updatedAt
        ) {
            consentTransitions.add(new ConsentTransition(profileId, consentStatus, deletedAt));
        }

        record ConsentTransition(String profileId, String newStatus, OffsetDateTime deletedAt) {}
    }

    private static class InMemoryEmbeddingRepo implements SpeakerEmbeddingRepository {
        final Map<String, Integer> deletedCount = new HashMap<>();

        @Override public void save(SpeakerEmbeddingRecord record) {}

        @Override public List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String profileId) {
            return List.of();
        }

        @Override public int revokeForProfile(String tenantId, String profileId, OffsetDateTime now) {
            return 0;
        }

        @Override public int deleteForProfile(String tenantId, String profileId, OffsetDateTime now) {
            return deletedCount.getOrDefault(profileId, 0);
        }
    }

    private static class RecordingDestroyer implements KmsKeyDestroyerPort {
        final Map<String, List<String>> keys = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        RuntimeException throwOnCall;

        @Override
        public List<String> destroyForSpeakerProfile(String tenantId, String profileId) {
            calls.add(profileId);
            if (throwOnCall != null) throw throwOnCall;
            return keys.getOrDefault(profileId, List.of());
        }
    }
}
