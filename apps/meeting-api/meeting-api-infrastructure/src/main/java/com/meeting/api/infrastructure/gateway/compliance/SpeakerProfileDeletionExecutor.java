package com.meeting.api.infrastructure.gateway.compliance;

import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionExecutorPort;
import com.meeting.api.domain.compliance.KmsKeyDestroyerPort;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SPEAKER_PROFILE-scope deletion executor.
 *
 * <p>Transitions the profile to {@code consent_status=DELETED},
 * soft-deletes every stored embedding row for the profile, and
 * records the KMS DEK ids that {@link KmsKeyDestroyerPort} reports
 * (or would have reported) destroyed.
 */
@Component
public class SpeakerProfileDeletionExecutor implements DeletionExecutorPort {

    private static final Logger log = LoggerFactory.getLogger(SpeakerProfileDeletionExecutor.class);

    private final SpeakerProfileRepository profileRepository;
    private final SpeakerEmbeddingRepository embeddingRepository;
    private final KmsKeyDestroyerPort kmsDestroyer;
    private final Clock clock;

    @Autowired
    public SpeakerProfileDeletionExecutor(
        SpeakerProfileRepository profileRepository,
        SpeakerEmbeddingRepository embeddingRepository,
        KmsKeyDestroyerPort kmsDestroyer
    ) {
        this(profileRepository, embeddingRepository, kmsDestroyer, Clock.systemUTC());
    }

    public SpeakerProfileDeletionExecutor(
        SpeakerProfileRepository profileRepository,
        SpeakerEmbeddingRepository embeddingRepository,
        KmsKeyDestroyerPort kmsDestroyer,
        Clock clock
    ) {
        this.profileRepository = profileRepository;
        this.embeddingRepository = embeddingRepository;
        this.kmsDestroyer = kmsDestroyer;
        this.clock = clock;
    }

    @Override
    public DeletionScopeType supportedScope() {
        return DeletionScopeType.SPEAKER_PROFILE;
    }

    @Override
    public DeletionOutcome execute(String tenantId, String scopeId, String executorId) {
        Map<String, Object> deletedRows = new LinkedHashMap<>();
        Map<String, Object> kmsKeys = new LinkedHashMap<>();
        java.util.List<String> failures = new java.util.ArrayList<>();

        Optional<SpeakerProfile> existing = profileRepository.findById(tenantId, scopeId);
        if (existing.isEmpty()) {
            failures.add("speaker_profile:" + scopeId + ":not_found");
            log.warn(
                "deletion_executor_speaker_profile_missing tenant={} profile={} executor={}",
                tenantId, scopeId, executorId
            );
            return new DeletionOutcome(deletedRows, Map.of(), kmsKeys, failures);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);

        // 1. Record + destroy DEKs FIRST. If KMS destruction later
        //    surfaces a failure, we still soft-delete the profile +
        //    embeddings so the operator can re-run with a different
        //    destroyer; the failed-items list captures the gap.
        List<String> destroyedKeyIds;
        try {
            destroyedKeyIds = kmsDestroyer.destroyForSpeakerProfile(tenantId, scopeId);
            if (!destroyedKeyIds.isEmpty()) {
                kmsKeys.put("speaker_dek_count", destroyedKeyIds.size());
                kmsKeys.put("speaker_dek_ids", destroyedKeyIds);
            }
        } catch (RuntimeException ex) {
            failures.add("kms:" + scopeId + ":" + ex.getClass().getSimpleName());
            log.warn(
                "deletion_executor_speaker_kms_failed tenant={} profile={} executor={} cause={}",
                tenantId, scopeId, executorId, ex.toString()
            );
        }

        // 2. Soft-delete embedding rows (cascade from profile delete).
        int embeddingsDeleted = embeddingRepository.deleteForProfile(tenantId, scopeId, now);
        if (embeddingsDeleted > 0) {
            deletedRows.put("speaker_embeddings", embeddingsDeleted);
        }

        // 3. Flip profile consent_status to DELETED.
        profileRepository.updateConsentStatus(
            tenantId, scopeId, /* consentStatus */ "DELETED",
            /* revokedAt */ existing.get().revokedAt() == null ? now : existing.get().revokedAt(),
            /* deletedAt */ now,
            /* updatedAt */ now
        );
        deletedRows.put("speaker_profiles", 1);

        log.info(
            "deletion_executor_speaker_profile_softdelete tenant={} profile={} executor={} embeddings={} keys={}",
            tenantId, scopeId, executorId, embeddingsDeleted,
            kmsKeys.getOrDefault("speaker_dek_count", 0)
        );
        return new DeletionOutcome(deletedRows, Map.of(), kmsKeys, failures);
    }
}
