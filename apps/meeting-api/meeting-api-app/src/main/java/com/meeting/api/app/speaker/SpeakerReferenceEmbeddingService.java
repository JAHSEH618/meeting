package com.meeting.api.app.speaker;

import com.meeting.api.domain.kms.EmbeddingEnvelopeGateway;
import com.meeting.api.domain.kms.EncryptedEmbedding;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository.SpeakerEmbeddingRecord;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Workstation D7 — resolve a centroid speaker embedding per person id.
 *
 * <p>For each person id:
 * <ul>
 *   <li>Look up the ACTIVE speaker_profile (filtered out REVOKED / DELETED at the repo).</li>
 *   <li>Fetch all ACTIVE embeddings for that profile (revoked + deleted excluded in memory).</li>
 *   <li>Decrypt each via {@link EmbeddingEnvelopeGateway} (KMS-wrapped DEK in the row).</li>
 *   <li>Average element-wise → centroid → L2-normalize.</li>
 * </ul>
 *
 * <p>Plaintext vectors are held only as method-local floats and zeroed before
 * returning. The response carries vectors over internal-TLS + HMAC; loggers in
 * this service NEVER print {@code values}, only counts + a SHA-256 fingerprint
 * of the source enrollment ids ({@code hash}).
 */
@Service
public class SpeakerReferenceEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(SpeakerReferenceEmbeddingService.class);

    private final SpeakerProfileRepository profileRepository;
    private final SpeakerEmbeddingRepository embeddingRepository;
    private final EmbeddingEnvelopeGateway envelopeGateway;
    private final Clock clock;

    @Autowired
    public SpeakerReferenceEmbeddingService(
        SpeakerProfileRepository profileRepository,
        SpeakerEmbeddingRepository embeddingRepository,
        EmbeddingEnvelopeGateway envelopeGateway
    ) {
        this(profileRepository, embeddingRepository, envelopeGateway, Clock.systemUTC());
    }
    public SpeakerReferenceEmbeddingService(
        SpeakerProfileRepository profileRepository,
        SpeakerEmbeddingRepository embeddingRepository,
        EmbeddingEnvelopeGateway envelopeGateway,
        Clock clock
    ) {
        this.profileRepository = profileRepository;
        this.embeddingRepository = embeddingRepository;
        this.envelopeGateway = envelopeGateway;
        this.clock = clock;
    }
    public List<ReferenceEmbedding> batchByPerson(String tenantId, List<String> personIds) {
        if (personIds == null || personIds.isEmpty()) {
            return List.of();
        }
        List<SpeakerProfile> profiles = profileRepository.findByPersonIds(tenantId, personIds);
        if (profiles.isEmpty()) {
            log.info("speaker_reference_no_profiles tenant={} requested={}", tenantId, personIds.size());
            return List.of();
        }
        // Group by personId so multiple enrollments per person fold into one centroid.
        Map<String, List<SpeakerProfile>> byPerson = profiles.stream()
            .filter(SpeakerProfile::isActive)
            .collect(Collectors.groupingBy(SpeakerProfile::personId, LinkedHashMap::new, Collectors.toList()));

        // FIX ⑨: fetch all embeddings for every active profile in ONE query instead of
        // one round-trip per profile, then group the rows by profileId in memory.
        List<String> profileIds = byPerson.values().stream()
            .flatMap(List::stream)
            .map(SpeakerProfile::id)
            .collect(Collectors.toList());
        List<SpeakerEmbeddingRecord> allRows = embeddingRepository.findByProfileIds(tenantId, profileIds);
        Map<String, List<SpeakerEmbeddingRecord>> rowsByProfile = allRows.stream()
            .collect(Collectors.groupingBy(SpeakerEmbeddingRecord::speakerProfileId));

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ReferenceEmbedding> result = new ArrayList<>(byPerson.size());
        for (var entry : byPerson.entrySet()) {
            String personId = entry.getKey();
            String speakerProfileId = entry.getValue().get(0).id();
            List<float[]> plaintextVectors = new ArrayList<>();
            List<String> sourceEnrollmentIds = new ArrayList<>();
            try {
                for (SpeakerProfile profile : entry.getValue()) {
                    List<SpeakerEmbeddingRecord> rows =
                        rowsByProfile.getOrDefault(profile.id(), List.of());
                    for (SpeakerEmbeddingRecord row : rows) {
                        if (row.revokedAt() != null || row.deletedAt() != null) continue;
                        if (!"ACTIVE".equals(row.consentStatus())) continue;
                        // Infra (KMS/DB) failures here propagate as-is → mapped to the 503
                        // SpeakerReferenceUnavailable path for the whole batch, which is correct
                        // for genuine infrastructure errors.
                        float[] decrypted = envelopeGateway.decrypt(tenantId, toEnvelope(row));
                        plaintextVectors.add(decrypted);
                        sourceEnrollmentIds.add(row.id());
                    }
                }
                // FIX ⑧: a person with no active/decryptable embeddings is NOT a batch-wide
                // failure. The contract says missing/revoked person ids are simply omitted
                // from `items`; the worker raises SpeakerReferenceUnavailable for that id.
                if (plaintextVectors.isEmpty()) {
                    log.info(
                        "speaker_reference_omitted_no_embeddings tenant={} personId={}",
                        tenantId, personId
                    );
                    continue;
                }
                float[] centroid = centroidL2Normalized(plaintextVectors);
                // FIX P3-8: a zero-norm centroid (degenerate average) can never match anything
                // (cosine 0). Treat it as a resolution failure and OMIT the person from items —
                // consistent with ⑧ — rather than emitting a guaranteed-non-matching vector.
                if (isZeroVector(centroid)) {
                    log.warn(
                        "speaker_reference_omitted_zero_norm tenant={} personId={} enrollments={} dim={}",
                        tenantId, personId, sourceEnrollmentIds.size(), centroid.length
                    );
                    continue;
                }
                String hash = sha256Of(sourceEnrollmentIds);
                result.add(new ReferenceEmbedding(personId, speakerProfileId, centroid, centroid.length, hash, now));
                log.info(
                    "speaker_reference_resolved tenant={} personId={} enrollments={} dim={} hash={}",
                    tenantId, personId, sourceEnrollmentIds.size(), centroid.length, hash
                );
            } finally {
                // Zero each decrypted vector so plaintext doesn't linger in heap.
                for (float[] v : plaintextVectors) {
                    Arrays.fill(v, 0f);
                }
            }
        }
        return result;
    }

    private static boolean isZeroVector(float[] v) {
        for (float f : v) {
            if (f != 0f) return false;
        }
        return true;
    }

    private static EncryptedEmbedding toEnvelope(SpeakerEmbeddingRecord row) {
        // The current schema stores ciphertext+tag in `embedding_ciphertext`; the
        // 12-byte GCM nonce is the prefix of that blob (matching what the gateway
        // emits at encrypt time when nonce is unbundled separately the call site
        // has historically packed [nonce || ciphertext+tag] into this column).
        byte[] raw = row.embeddingCiphertext();
        byte[] nonce;
        byte[] ciphertext;
        if (raw != null && raw.length > 12) {
            nonce = new byte[12];
            System.arraycopy(raw, 0, nonce, 0, 12);
            ciphertext = new byte[raw.length - 12];
            System.arraycopy(raw, 12, ciphertext, 0, ciphertext.length);
        } else {
            nonce = new byte[12];
            ciphertext = raw == null ? new byte[0] : raw;
        }
        return new EncryptedEmbedding(
            ciphertext,
            nonce,
            row.wrappedDataKey(),
            row.encryptionKeyId(),
            row.encryptionAlgorithm(),
            row.embeddingHash()
        );
    }

    private static float[] centroidL2Normalized(List<float[]> vectors) {
        int dim = vectors.get(0).length;
        double[] sum = new double[dim];
        for (float[] v : vectors) {
            if (v.length != dim) {
                throw new IllegalStateException(
                    "embedding dimension mismatch: expected " + dim + " got " + v.length
                );
            }
            for (int i = 0; i < dim; i++) sum[i] += v[i];
        }
        double[] avg = new double[dim];
        for (int i = 0; i < dim; i++) avg[i] = sum[i] / vectors.size();
        double norm = 0;
        for (double a : avg) norm += a * a;
        norm = Math.sqrt(norm);
        float[] out = new float[dim];
        if (norm == 0) {
            return out; // all-zero vector stays all-zero (degenerate but safe).
        }
        for (int i = 0; i < dim; i++) out[i] = (float) (avg[i] / norm);
        return out;
    }

    private static String sha256Of(List<String> ids) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String id : ids) {
                md.update(id.getBytes());
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
    public record ReferenceEmbedding(
        String personId,
        String speakerProfileId,
        float[] values,
        int dim,
        String hash,
        OffsetDateTime computedAt
    ) {
    }
}
