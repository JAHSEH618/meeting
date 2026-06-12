package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.speaker.SpeakerEnrollmentCallbackApplicationService;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.SpeakerEnrollmentCallbackCommand;
import com.meeting.api.domain.kms.EmbeddingEnvelopeGateway;
import com.meeting.api.domain.kms.EncryptedEmbedding;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerEnrollmentRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeakerEnrollmentCallbackApplicationServiceTest {
    private static final String SECRET = "callback-secret";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-02T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @Test
    void writeEnrollmentStoresEncryptedEmbeddingAndMarksEnrollmentSucceeded() {
        Fixtures fx = fixtures();
        float[] values = new float[] {0.25f, -0.5f};

        var result = fx.service().writeEnrollment(command("idem_01", "profile_01", "enroll_01", "audio_01", values));

        assertThat(result.replayed()).isFalse();
        assertThat(result.enrollmentId()).isEqualTo("enroll_01");
        assertThat(fx.callbacks.records).hasSize(1);
        assertThat(fx.embeddings.saved).singleElement().satisfies(record -> {
            assertThat(record.tenantId()).isEqualTo("tenant_01");
            assertThat(record.speakerProfileId()).isEqualTo("profile_01");
            assertThat(record.personId()).isEqualTo("person_01");
            assertThat(record.consentStatus()).isEqualTo("ACTIVE");
            assertThat(record.encryptionKeyId()).isEqualTo("key_01");
            assertThat(record.wrappedDataKey()).containsExactly(7);
            assertThat(record.encryptionAlgorithm()).isEqualTo("AES-256-GCM");
            assertThat(record.embeddingCiphertext()).containsExactly(8, 9);
            assertThat(record.embeddingHash()).isEqualTo("hash_01");
            assertThat(record.sourceAudioFileId()).isEqualTo("audio_01");
            assertThat(record.qualityScore()).isEqualTo(0.93);
            assertThat(record.modelVersion()).isEqualTo("speaker-test-v1");
            assertThat(record.createdAt()).isEqualTo(NOW);
        });
        assertThat(fx.enrollments.updates).singleElement().satisfies(update -> {
            assertThat(update.enrollmentId()).isEqualTo("enroll_01");
            assertThat(update.status()).isEqualTo("SUCCEEDED");
            assertThat(update.qualityScore()).isEqualTo(0.93);
            assertThat(update.modelVersion()).isEqualTo("speaker-test-v1");
            assertThat(update.errorCode()).isNull();
            assertThat(update.updatedAt()).isEqualTo(NOW);
        });
        assertThat(values).containsExactly(0f, 0f);
    }

    @Test
    void idempotentReplayWithSameBodyHashDoesNotWriteTwice() {
        Fixtures fx = fixtures();
        var command = command("idem_replay", "profile_01", "enroll_01", "audio_01", new float[] {0.1f, 0.2f});

        fx.service().writeEnrollment(command);
        var replay = fx.service().writeEnrollment(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(fx.callbacks.records).hasSize(1);
        assertThat(fx.embeddings.saved).hasSize(1);
        assertThat(fx.enrollments.updates).hasSize(1);
    }

    @Test
    void rejectsEnrollmentThatBelongsToAnotherProfile() {
        Fixtures fx = fixtures();
        fx.profiles.profiles.put("profile_02", profile("profile_02", "person_02", "ACTIVE"));

        assertThatThrownBy(() -> fx.service().writeEnrollment(
            command("idem_mismatch", "profile_02", "enroll_01", "audio_01", new float[] {0.1f})
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not belong to profile");

        assertThat(fx.embeddings.saved).isEmpty();
        assertThat(fx.enrollments.updates).isEmpty();
    }

    @Test
    void rejectsRevokedProfile() {
        Fixtures fx = fixtures();
        fx.profiles.profiles.put("profile_01", profile("profile_01", "person_01", "REVOKED"));

        assertThatThrownBy(() -> fx.service().writeEnrollment(
            command("idem_revoked", "profile_01", "enroll_01", "audio_01", new float[] {0.1f})
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not ACTIVE");

        assertThat(fx.embeddings.saved).isEmpty();
        assertThat(fx.enrollments.updates).isEmpty();
    }

    @Test
    void rejectsAudioFileMismatch() {
        Fixtures fx = fixtures();

        assertThatThrownBy(() -> fx.service().writeEnrollment(
            command("idem_audio", "profile_01", "enroll_01", "audio_other", new float[] {0.1f})
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("audio file does not match");

        assertThat(fx.embeddings.saved).isEmpty();
        assertThat(fx.enrollments.updates).isEmpty();
    }

    @Test
    void rejectsNonEnrollmentTask() {
        Fixtures fx = fixtures();
        fx.tasks.task = runningTask("MEETING_FULL_PIPELINE", "meeting_01");

        assertThatThrownBy(() -> fx.service().writeEnrollment(
            command("idem_task_type", "profile_01", "enroll_01", "audio_01", new float[] {0.1f})
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not SPEAKER_ENROLLMENT");

        assertThat(fx.embeddings.saved).isEmpty();
        assertThat(fx.enrollments.updates).isEmpty();
    }

    @Test
    void rejectsLeaseMismatchBeforeWriting() {
        Fixtures fx = fixtures();
        var command = command(
            "idem_lease",
            "profile_01",
            "enroll_01",
            "audio_01",
            new float[] {0.1f},
            metadata("idem_lease", "{\"body\":\"idem_lease\"}", "other_worker:task_01:1")
        );

        assertThatThrownBy(() -> fx.service().writeEnrollment(command))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lease owner");

        assertThat(fx.callbacks.records).isEmpty();
        assertThat(fx.embeddings.saved).isEmpty();
    }

    private static Fixtures fixtures() {
        return new Fixtures();
    }

    private static final class Fixtures {
        final InMemoryTasks tasks = new InMemoryTasks(runningTask("SPEAKER_ENROLLMENT", null));
        final InMemoryCallbackEvents callbacks = new InMemoryCallbackEvents();
        final InMemoryProfiles profiles = new InMemoryProfiles();
        final InMemoryEnrollments enrollments = new InMemoryEnrollments();
        final CapturingSpeakerEmbeddings embeddings = new CapturingSpeakerEmbeddings();
        final StubEnvelopeGateway envelope = new StubEnvelopeGateway();

        Fixtures() {
            profiles.profiles.put("profile_01", profile("profile_01", "person_01", "ACTIVE"));
            enrollments.records.put("enroll_01", new SpeakerEnrollmentRepository.SpeakerEnrollmentRecord(
                "enroll_01",
                "tenant_01",
                "profile_01",
                "audio_01",
                "PENDING",
                null,
                null,
                null,
                null,
                "user_01",
                NOW,
                NOW
            ));
        }

        SpeakerEnrollmentCallbackApplicationService service() {
            return new SpeakerEnrollmentCallbackApplicationService(
                tasks,
                callbacks,
                profiles,
                enrollments,
                embeddings,
                envelope,
                TenantScopedTransaction.immediate(),
                new CallbackSecurityVerifier(SECRET, 300, CLOCK),
                CLOCK
            );
        }
    }

    private static ProcessingTask runningTask(String taskType, String meetingId) {
        List<ProcessingStep> steps = "SPEAKER_ENROLLMENT".equals(taskType)
            ? List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING)
            : List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.SPEAKER_MATCHING);
        ProcessingTask task = ProcessingTask.create("task_01", "tenant_01", meetingId, taskType, steps, NOW);
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        return task;
    }

    private static SpeakerProfile profile(String profileId, String personId, String consentStatus) {
        return SpeakerProfile.restore(
            profileId,
            "tenant_01",
            personId,
            "Profile " + profileId,
            consentStatus,
            "INVITE",
            "v1",
            "user_01",
            "REVOKED".equals(consentStatus) ? NOW.minusDays(1) : null,
            null,
            NOW.minusDays(2),
            NOW
        );
    }

    private static SpeakerEnrollmentCallbackCommand command(
        String idempotencyKey,
        String speakerProfileId,
        String speakerEnrollmentId,
        String audioFileId,
        float[] values
    ) {
        String body = "{\"body\":\"" + idempotencyKey + "\"}";
        return command(idempotencyKey, speakerProfileId, speakerEnrollmentId, audioFileId, values,
            metadata(idempotencyKey, body, "worker_01:task_01:1"));
    }

    private static SpeakerEnrollmentCallbackCommand command(
        String idempotencyKey,
        String speakerProfileId,
        String speakerEnrollmentId,
        String audioFileId,
        float[] values,
        CallbackMetadata metadata
    ) {
        return new SpeakerEnrollmentCallbackCommand(
            metadata,
            "tenant_01",
            "task_01",
            1,
            speakerProfileId,
            speakerEnrollmentId,
            audioFileId,
            new SpeakerEnrollmentCallbackCommand.PlainEmbedding(
                "FLOAT32_ARRAY",
                values.length,
                values,
                "checksum_01",
                "speaker-test-v1",
                0.93
            ),
            null
        );
    }

    private static CallbackMetadata metadata(String idempotencyKey, String body, String leaseOwner) {
        String bodyHash = sha256(body);
        String method = "POST";
        String path = "/internal/processing-tasks/task_01/speaker-enrollment";
        String nonce = "nonce_" + idempotencyKey;
        String signingString = NOW + "\n" + nonce + "\n" + method + "\n" + path + "\n" + bodyHash;
        return new CallbackMetadata(
            "worker_01",
            1,
            leaseOwner,
            method,
            "req_" + idempotencyKey,
            "trace_" + idempotencyKey,
            NOW,
            nonce,
            idempotencyKey,
            "hmac-sha256=" + hmac(signingString),
            path,
            bodyHash
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class InMemoryTasks implements ProcessingTaskRepository {
        ProcessingTask task;

        InMemoryTasks(ProcessingTask task) {
            this.task = task;
        }

        @Override public ProcessingTask save(ProcessingTask task) {
            this.task = task;
            return task;
        }

        @Override public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return task != null && tenantId.equals(task.tenantId()) && taskId.equals(task.taskId())
                ? Optional.of(task)
                : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return findById(tenantId, taskId);
        }

        @Override public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return Optional.empty();
        }

        @Override public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }

    private static final class InMemoryCallbackEvents implements CallbackEventRepository {
        final List<CallbackEventRecord> records = new ArrayList<>();

        @Override public Optional<CallbackEventRecord> findByIdempotencyKey(String tenantId, String idempotencyKey) {
            return records.stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> idempotencyKey.equals(record.idempotencyKey()))
                .findFirst();
        }

        @Override public CallbackEventRecord save(CallbackEventRecord record) {
            records.add(record);
            return record;
        }
    }

    private static final class InMemoryProfiles implements SpeakerProfileRepository {
        final Map<String, SpeakerProfile> profiles = new LinkedHashMap<>();

        @Override public SpeakerProfile save(SpeakerProfile profile) {
            profiles.put(profile.id(), profile);
            return profile;
        }

        @Override public Optional<SpeakerProfile> findById(String tenantId, String profileId) {
            return Optional.ofNullable(profiles.get(profileId))
                .filter(profile -> tenantId.equals(profile.tenantId()));
        }

        @Override public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) {
            return profiles.values().stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> includeRevoked || profile.isActive())
                .toList();
        }

        @Override public List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds) {
            return profiles.values().stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> profileIds.contains(profile.id()))
                .toList();
        }

        @Override public List<SpeakerProfile> findByPersonIds(String tenantId, List<String> personIds) {
            return profiles.values().stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> personIds.contains(profile.personId()))
                .filter(SpeakerProfile::isActive)
                .toList();
        }

        @Override public void updateConsentStatus(
            String tenantId,
            String profileId,
            String consentStatus,
            OffsetDateTime revokedAt,
            OffsetDateTime deletedAt,
            OffsetDateTime updatedAt
        ) {
        }
    }

    private static final class InMemoryEnrollments implements SpeakerEnrollmentRepository {
        final Map<String, SpeakerEnrollmentRecord> records = new LinkedHashMap<>();
        final List<StatusUpdate> updates = new ArrayList<>();

        @Override public String save(SpeakerEnrollmentRecord record) {
            records.put(record.id(), record);
            return record.id();
        }

        @Override public Optional<SpeakerEnrollmentRecord> findById(String tenantId, String enrollmentId) {
            return Optional.ofNullable(records.get(enrollmentId))
                .filter(record -> tenantId.equals(record.tenantId()));
        }

        @Override public List<SpeakerEnrollmentRecord> findByProfile(String tenantId, String profileId) {
            return records.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> profileId.equals(record.speakerProfileId()))
                .toList();
        }

        @Override public void updateStatus(
            String tenantId,
            String enrollmentId,
            String enrollmentStatus,
            Double qualityScore,
            String modelVersion,
            String errorCode,
            OffsetDateTime now
        ) {
            updates.add(new StatusUpdate(enrollmentId, enrollmentStatus, qualityScore, modelVersion, errorCode, now));
        }
    }

    private record StatusUpdate(
        String enrollmentId,
        String status,
        Double qualityScore,
        String modelVersion,
        String errorCode,
        OffsetDateTime updatedAt
    ) {
    }

    private static final class CapturingSpeakerEmbeddings implements SpeakerEmbeddingRepository {
        final List<SpeakerEmbeddingRecord> saved = new ArrayList<>();

        @Override public void save(SpeakerEmbeddingRecord record) {
            saved.add(record);
        }

        @Override public List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String speakerProfileId) {
            return List.of();
        }

        @Override public int revokeForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) {
            return 0;
        }

        @Override public int deleteForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) {
            return 0;
        }
    }

    private static final class StubEnvelopeGateway implements EmbeddingEnvelopeGateway {
        @Override public EncryptedEmbedding encrypt(String tenantId, float[] embedding) {
            return new EncryptedEmbedding(new byte[] {9}, new byte[] {8}, new byte[] {7}, "key_01", "AES-256-GCM", "hash_01");
        }

        @Override public float[] decrypt(String tenantId, EncryptedEmbedding payload) {
            return new float[0];
        }
    }
}
