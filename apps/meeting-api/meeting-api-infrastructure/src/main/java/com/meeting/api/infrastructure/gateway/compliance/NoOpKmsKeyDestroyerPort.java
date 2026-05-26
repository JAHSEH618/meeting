package com.meeting.api.infrastructure.gateway.compliance;

import com.meeting.api.domain.compliance.KmsKeyDestroyerPort;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository.SpeakerEmbeddingRecord;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Phase-1 {@link KmsKeyDestroyerPort} that walks the existing speaker
 * embedding rows to discover encrypted DEK ids and records them in
 * the returned list. It does NOT actually contact Vault / KMS —
 * destruction of the real key material is left to the production
 * deployment's vault tooling. Replacing this bean with a vault-backed
 * implementation is enough to make destruction real.
 */
public class NoOpKmsKeyDestroyerPort implements KmsKeyDestroyerPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpKmsKeyDestroyerPort.class);

    private final SpeakerEmbeddingRepository embeddingRepository;

    public NoOpKmsKeyDestroyerPort(SpeakerEmbeddingRepository embeddingRepository) {
        this.embeddingRepository = embeddingRepository;
    }

    @Override
    public List<String> destroyForSpeakerProfile(String tenantId, String speakerProfileId) {
        List<SpeakerEmbeddingRecord> embeddings =
            embeddingRepository.findByProfile(tenantId, speakerProfileId);
        if (embeddings.isEmpty()) {
            return List.of();
        }
        List<String> keyIds = new ArrayList<>(embeddings.size());
        for (SpeakerEmbeddingRecord row : embeddings) {
            keyIds.add(row.encryptionKeyId());
        }
        log.info(
            "kms_key_destroyer_recorded tenant={} profile={} keys={}",
            tenantId, speakerProfileId, keyIds.size()
        );
        return List.copyOf(keyIds);
    }
}
