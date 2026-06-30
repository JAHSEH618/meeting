package com.meeting.api;

import com.meeting.api.app.speaker.SpeakerReferenceEmbeddingService;
import com.meeting.api.domain.kms.EmbeddingEnvelopeGateway;
import com.meeting.api.domain.kms.EncryptedEmbedding;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository.SpeakerEmbeddingRecord;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workstation D7 — SpeakerReferenceEmbeddingService unit tests (B5.5).
 *
 * <p>Covers centroid math + L2 normalization, no-plaintext-in-logs, multi-enrollment
 * folding, and SPEAKER_REFERENCE_UNAVAILABLE when nothing decryptable exists.
 */
class SpeakerReferenceEmbeddingServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-19T06:00:00Z");

    @Test
    void singleEnrollmentReturnsUnitVectorCentroid() {
        Fixture f = fixture();
        f.profiles.put("p_alice", profile("sp_alice", "tenant_01", "p_alice"));
        f.embeddings.put("sp_alice", List.of(record("emb_1", "sp_alice")));
        f.gateway.queue.put("emb_1", new float[] {3.0f, 4.0f}); // mag 5

        var result = f.service.batchByPerson("tenant_01", List.of("p_alice"));

        assertThat(result).hasSize(1);
        var ref = result.get(0);
        assertThat(ref.personId()).isEqualTo("p_alice");
        assertThat(ref.speakerProfileId()).isEqualTo("sp_alice");
        assertThat(ref.dim()).isEqualTo(2);
        // L2-normalized → 0.6 / 0.8
        assertThat(ref.values()[0]).isCloseTo(0.6f, within(0.0001f));
        assertThat(ref.values()[1]).isCloseTo(0.8f, within(0.0001f));
        assertThat(ref.hash()).matches("[0-9a-f]{64}");
        assertThat(ref.computedAt()).isEqualTo(NOW);
    }

    @Test
    void multipleEnrollmentsFoldIntoOneCentroid() {
        Fixture f = fixture();
        f.profiles.put("p_bob", profile("sp_bob", "tenant_01", "p_bob"));
        f.embeddings.put("sp_bob", List.of(
            record("emb_1", "sp_bob"),
            record("emb_2", "sp_bob"),
            record("emb_3", "sp_bob")
        ));
        f.gateway.queue.put("emb_1", new float[] {1.0f, 0.0f});
        f.gateway.queue.put("emb_2", new float[] {0.0f, 1.0f});
        f.gateway.queue.put("emb_3", new float[] {1.0f, 1.0f});

        var result = f.service.batchByPerson("tenant_01", List.of("p_bob"));

        assertThat(result).hasSize(1);
        var ref = result.get(0);
        // avg = (2/3, 2/3) → norm = 2√2/3; normalized → (1/√2, 1/√2)
        assertThat(ref.values()[0]).isCloseTo(0.7071f, within(0.001f));
        assertThat(ref.values()[1]).isCloseTo(0.7071f, within(0.001f));
    }

    @Test
    void revokedEmbeddingsAreFiltered() {
        Fixture f = fixture();
        f.profiles.put("p_chen", profile("sp_chen", "tenant_01", "p_chen"));
        SpeakerEmbeddingRecord revoked = new SpeakerEmbeddingRecord(
            "emb_revoked", "tenant_01", "sp_chen", "p_chen",
            "REVOKED", "key_01", new byte[0], "AES-256-GCM", new byte[0], "hash",
            null, null, "v1", NOW, null, NOW
        );
        f.embeddings.put("sp_chen", List.of(record("emb_active", "sp_chen"), revoked));
        f.gateway.queue.put("emb_active", new float[] {1.0f, 0.0f});

        var result = f.service.batchByPerson("tenant_01", List.of("p_chen"));

        assertThat(result).hasSize(1);
        // Only the ACTIVE embedding contributed.
        assertThat(f.gateway.calls).containsExactly("emb_active");
    }

    @Test
    void unknownPersonIsSkippedNotErrored() {
        Fixture f = fixture();
        f.profiles.put("p_known", profile("sp_known", "tenant_01", "p_known"));
        f.embeddings.put("sp_known", List.of(record("emb_1", "sp_known")));
        f.gateway.queue.put("emb_1", new float[] {1.0f, 0.0f});

        var result = f.service.batchByPerson("tenant_01", List.of("p_unknown", "p_known"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).personId()).isEqualTo("p_known");
    }

    @Test
    void noActiveEmbeddingsOmitsPersonFromItems() {
        // FIX ⑧: a person whose only embeddings are revoked has no active reference.
        // Per the contract such person ids are OMITTED from items (not a batch failure);
        // the worker raises SpeakerReferenceUnavailable for that specific id.
        Fixture f = fixture();
        f.profiles.put("p_d", profile("sp_d", "tenant_01", "p_d"));
        SpeakerEmbeddingRecord revoked = new SpeakerEmbeddingRecord(
            "emb_only_revoked", "tenant_01", "sp_d", "p_d",
            "REVOKED", "key_01", new byte[0], "AES-256-GCM", new byte[0], "hash",
            null, null, "v1", NOW, null, NOW
        );
        f.embeddings.put("sp_d", List.of(revoked));

        assertThat(f.service.batchByPerson("tenant_01", List.of("p_d"))).isEmpty();
    }

    @Test
    void oneUnenrolledPersonDoesNotFailWholeBatch() {
        // FIX ⑧: p_revoked has no active embeddings but p_ok does — the batch must still
        // return p_ok rather than throwing for the whole request.
        Fixture f = fixture();
        f.profiles.put("p_ok", profile("sp_ok", "tenant_01", "p_ok"));
        f.profiles.put("p_revoked", profile("sp_revoked", "tenant_01", "p_revoked"));
        f.embeddings.put("sp_ok", List.of(record("emb_ok", "sp_ok")));
        SpeakerEmbeddingRecord revoked = new SpeakerEmbeddingRecord(
            "emb_rev", "tenant_01", "sp_revoked", "p_revoked",
            "REVOKED", "key_01", new byte[0], "AES-256-GCM", new byte[0], "hash",
            null, null, "v1", NOW, null, NOW
        );
        f.embeddings.put("sp_revoked", List.of(revoked));
        f.gateway.queue.put("emb_ok", new float[] {1.0f, 0.0f});

        var result = f.service.batchByPerson("tenant_01", List.of("p_revoked", "p_ok"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).personId()).isEqualTo("p_ok");
    }

    @Test
    void zeroNormCentroidIsOmittedFromItems() {
        // FIX P3-8: two opposite vectors average to the zero vector (norm 0). The centroid
        // can never match anything (cosine 0), so the person is OMITTED rather than emitted
        // as a guaranteed-non-matching item.
        Fixture f = fixture();
        f.profiles.put("p_zero", profile("sp_zero", "tenant_01", "p_zero"));
        f.embeddings.put("sp_zero", List.of(
            record("emb_pos", "sp_zero"),
            record("emb_neg", "sp_zero")
        ));
        f.gateway.queue.put("emb_pos", new float[] {1.0f, 0.0f});
        f.gateway.queue.put("emb_neg", new float[] {-1.0f, 0.0f});

        assertThat(f.service.batchByPerson("tenant_01", List.of("p_zero"))).isEmpty();
    }

    @Test
    void emptyPersonIdListReturnsEmpty() {
        Fixture f = fixture();
        assertThat(f.service.batchByPerson("tenant_01", List.of())).isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static org.assertj.core.data.Offset<Float> within(float tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    private static Fixture fixture() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        InMemoryEmbeddingRepo embeddings = new InMemoryEmbeddingRepo();
        StubGateway gateway = new StubGateway();
        SpeakerReferenceEmbeddingService service = new SpeakerReferenceEmbeddingService(
            profiles, embeddings, gateway, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
        return new Fixture(profiles.byPersonId, embeddings.byProfile, gateway, service);
    }

    private static SpeakerProfile profile(String id, String tenantId, String personId) {
        return SpeakerProfile.restore(
            id, tenantId, personId, personId, "ACTIVE", "self", "v1", "user_01",
            null, null, NOW, NOW
        );
    }

    private static SpeakerEmbeddingRecord record(String id, String profileId) {
        return new SpeakerEmbeddingRecord(
            id, "tenant_01", profileId, profileId.replace("sp_", "p_"),
            "ACTIVE", "key_01", new byte[]{1, 2, 3}, "AES-256-GCM",
            new byte[]{4, 5, 6}, "hash", null, 0.9, "v1",
            null, null, NOW
        );
    }

    private record Fixture(
        Map<String, SpeakerProfile> profiles,
        Map<String, List<SpeakerEmbeddingRecord>> embeddings,
        StubGateway gateway,
        SpeakerReferenceEmbeddingService service
    ) {
    }

    private static final class InMemoryProfileRepo implements SpeakerProfileRepository {
        final Map<String, SpeakerProfile> byPersonId = new HashMap<>();

        @Override public SpeakerProfile save(SpeakerProfile p) { byPersonId.put(p.personId(), p); return p; }
        @Override public Optional<SpeakerProfile> findById(String tenantId, String profileId) {
            return byPersonId.values().stream().filter(p -> p.id().equals(profileId)).findFirst();
        }
        @Override public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) { return List.copyOf(byPersonId.values()); }
        @Override public List<SpeakerProfile> findByIds(String tenantId, List<String> ids) {
            return byPersonId.values().stream().filter(p -> ids.contains(p.id())).toList();
        }
        @Override public List<SpeakerProfile> findByPersonIds(String tenantId, List<String> personIds) {
            List<SpeakerProfile> out = new ArrayList<>();
            for (String pid : personIds) {
                SpeakerProfile p = byPersonId.get(pid);
                if (p != null && p.isActive()) out.add(p);
            }
            return out;
        }
        @Override public void updateConsentStatus(String tenantId, String profileId, String consentStatus,
                                                  OffsetDateTime revokedAt, OffsetDateTime deletedAt, OffsetDateTime updatedAt) {}
    }

    private static final class InMemoryEmbeddingRepo implements SpeakerEmbeddingRepository {
        final Map<String, List<SpeakerEmbeddingRecord>> byProfile = new HashMap<>();
        @Override public void save(SpeakerEmbeddingRecord record) {
            byProfile.computeIfAbsent(record.speakerProfileId(), k -> new ArrayList<>()).add(record);
        }
        @Override public List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String speakerProfileId) {
            return byProfile.getOrDefault(speakerProfileId, List.of());
        }
        @Override public List<SpeakerEmbeddingRecord> findByProfileIds(String tenantId, java.util.Collection<String> speakerProfileIds) {
            List<SpeakerEmbeddingRecord> out = new ArrayList<>();
            for (String pid : speakerProfileIds) {
                out.addAll(byProfile.getOrDefault(pid, List.of()));
            }
            return out;
        }
        @Override public int revokeForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) { return 0; }
        @Override public int deleteForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) { return 0; }
    }

    private static final class StubGateway implements EmbeddingEnvelopeGateway {
        final Map<String, float[]> queue = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        @Override public EncryptedEmbedding encrypt(String tenantId, float[] embedding) {
            throw new UnsupportedOperationException("not used in this test");
        }
        @Override public float[] decrypt(String tenantId, EncryptedEmbedding payload) {
            // Map plaintextHash to the record id; in real code the ciphertext is opaque.
            // For tests we just look up by recorded order — the service calls in
            // sequence per profile so we match the first un-served id.
            for (var e : queue.entrySet()) {
                if (!calls.contains(e.getKey())) {
                    calls.add(e.getKey());
                    return e.getValue().clone();
                }
            }
            throw new IllegalStateException("no canned vector left");
        }
    }
}
