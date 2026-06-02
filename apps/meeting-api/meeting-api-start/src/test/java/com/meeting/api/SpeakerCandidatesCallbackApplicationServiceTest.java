package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.speaker.SpeakerCandidatesCallbackApplicationService;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.SpeakerCandidatesCallbackCommand;
import com.meeting.api.domain.kms.EmbeddingEnvelopeGateway;
import com.meeting.api.domain.kms.EncryptedEmbedding;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
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
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerCandidatesCallbackApplicationServiceTest {
    private static final String SECRET = "callback-secret";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-02T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @Test
    void writeCandidatesStoresOnlyAuthorizedFullCandidateObjects() {
        InMemorySpeakers speakers = new InMemorySpeakers();
        SpeakerCandidatesCallbackApplicationService service = service(speakers, new InMemoryProfiles(List.of(
            SpeakerProfile.restore(
                "profile_01",
                "tenant_01",
                "person_01",
                "Alice Profile",
                "ACTIVE",
                "INVITE",
                "v1",
                "user_01",
                null,
                null,
                NOW,
                NOW
            ),
            SpeakerProfile.restore(
                "profile_02",
                "tenant_01",
                "person_02",
                "Revoked Profile",
                "REVOKED",
                "INVITE",
                "v1",
                "user_01",
                NOW.minusDays(1),
                null,
                NOW.minusDays(2),
                NOW.minusDays(1)
            )
        )));

        service.writeCandidates(new SpeakerCandidatesCallbackCommand(
            metadata("POST", "/internal/processing-tasks/task_01/speaker-candidates", "{}"),
            "tenant_01",
            "meeting_01",
            "task_01",
            1,
            List.of(new SpeakerCandidatesCallbackCommand.SpeakerEntry(
                "SPEAKER_00",
                List.of(
                    new SpeakerCandidatesCallbackCommand.Candidate("person_01", "profile_01", 0.91, "MATCH"),
                    new SpeakerCandidatesCallbackCommand.Candidate("person_02", "profile_02", 0.88, "MATCH"),
                    new SpeakerCandidatesCallbackCommand.Candidate("person_03", null, 0.77, "MATCH")
                ),
                null
            ))
        ));

        assertThat(speakers.savedCandidates).containsExactly(new MeetingSpeakerRepository.SpeakerCandidate(
            "person_01",
            "profile_01",
            0.91
        ));
        assertThat(speakers.savedCandidatePersonIds).containsExactly("person_01");
        assertThat(speakers.savedAutoMatchScore).isEqualTo(0.91);
    }

    private static SpeakerCandidatesCallbackApplicationService service(
        MeetingSpeakerRepository meetingSpeakerRepository,
        SpeakerProfileRepository speakerProfileRepository
    ) {
        return new SpeakerCandidatesCallbackApplicationService(
            new InMemoryTasks(),
            new InMemoryCallbackEvents(),
            speakerProfileRepository,
            new NoopSpeakerEmbeddings(),
            meetingSpeakerRepository,
            new NoopEnvelopeGateway(),
            TenantScopedTransaction.immediate(),
            new CallbackSecurityVerifier(SECRET, 300, CLOCK),
            CLOCK
        );
    }

    private static CallbackMetadata metadata(String method, String path, String body) {
        String bodyHash = sha256(body);
        OffsetDateTime timestamp = NOW;
        String nonce = "nonce_01";
        String signingString = timestamp + "\n" + nonce + "\n" + method + "\n" + path + "\n" + bodyHash;
        return new CallbackMetadata(
            "worker_01",
            1,
            "worker_01:task_01:1",
            method,
            "req_01",
            "trace_01",
            timestamp,
            nonce,
            "idem_01",
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
        private final ProcessingTask task = ProcessingTask.create(
            "task_01",
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            List.of(
                ProcessingStep.AUDIO_PREPROCESS,
                ProcessingStep.ASR,
                ProcessingStep.SPEAKER_MATCHING,
                ProcessingStep.TRANSCRIPT_MERGE
            ),
            NOW
        );

        @Override
        public ProcessingTask save(ProcessingTask task) {
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return tenantId.equals(task.tenantId()) && taskId.equals(task.taskId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }

    private static final class InMemoryCallbackEvents implements CallbackEventRepository {
        private final List<CallbackEventRecord> records = new ArrayList<>();

        @Override
        public Optional<CallbackEventRecord> findByIdempotencyKey(String tenantId, String idempotencyKey) {
            return records.stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> idempotencyKey.equals(record.idempotencyKey()))
                .findFirst();
        }

        @Override
        public CallbackEventRecord save(CallbackEventRecord record) {
            records.add(record);
            return record;
        }
    }

    private static final class InMemoryProfiles implements SpeakerProfileRepository {
        private final List<SpeakerProfile> profiles;

        private InMemoryProfiles(List<SpeakerProfile> profiles) {
            this.profiles = profiles;
        }

        @Override
        public SpeakerProfile save(SpeakerProfile profile) {
            return profile;
        }

        @Override
        public Optional<SpeakerProfile> findById(String tenantId, String profileId) {
            return profiles.stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> profileId.equals(profile.id()))
                .findFirst();
        }

        @Override
        public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) {
            return profiles.stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> includeRevoked || profile.isActive())
                .toList();
        }

        @Override
        public List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds) {
            return profiles.stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> profileIds.contains(profile.id()))
                .toList();
        }

        @Override
        public void updateConsentStatus(
            String tenantId,
            String profileId,
            String consentStatus,
            OffsetDateTime revokedAt,
            OffsetDateTime deletedAt,
            OffsetDateTime updatedAt
        ) {
        }
    }

    private static final class InMemorySpeakers implements MeetingSpeakerRepository {
        private List<MeetingSpeakerRepository.SpeakerCandidate> savedCandidates = List.of();
        private List<String> savedCandidatePersonIds = List.of();
        private Double savedAutoMatchScore;

        @Override
        public Optional<MeetingSpeakerRecord> find(String tenantId, String meetingId, String speakerLabel) {
            return Optional.empty();
        }

        @Override
        public List<MeetingSpeakerRecord> findByMeeting(String tenantId, String meetingId) {
            return List.of();
        }

        @Override
        public List<String> findMeetingIdsByConfirmedPerson(String tenantId, String personId) {
            return List.of();
        }

        @Override
        public void saveCandidates(
            String tenantId,
            String meetingId,
            String speakerLabel,
            List<String> candidatePersonIds,
            Double autoMatchScore,
            String matchSource,
            OffsetDateTime now
        ) {
            throw new AssertionError("speaker candidates callback must preserve full candidate profile metadata");
        }

        @Override
        public void saveCandidates(
            String tenantId,
            String meetingId,
            String speakerLabel,
            List<String> candidatePersonIds,
            List<MeetingSpeakerRepository.SpeakerCandidate> candidates,
            Double autoMatchScore,
            String matchSource,
            OffsetDateTime now
        ) {
            savedCandidatePersonIds = candidatePersonIds;
            savedCandidates = candidates;
            savedAutoMatchScore = autoMatchScore;
        }

        @Override
        public void confirm(
            String tenantId,
            String meetingId,
            String speakerLabel,
            String confirmedPersonId,
            String confirmedBy,
            OffsetDateTime now
        ) {
        }

        @Override
        public void reject(String tenantId, String meetingId, String speakerLabel, String rejectedBy, OffsetDateTime now) {
        }
    }

    private static final class NoopSpeakerEmbeddings implements SpeakerEmbeddingRepository {
        @Override
        public void save(SpeakerEmbeddingRecord record) {
        }

        @Override
        public List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String speakerProfileId) {
            return List.of();
        }

        @Override
        public int revokeForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) {
            return 0;
        }

        @Override
        public int deleteForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) {
            return 0;
        }
    }

    private static final class NoopEnvelopeGateway implements EmbeddingEnvelopeGateway {
        @Override
        public EncryptedEmbedding encrypt(String tenantId, float[] embedding) {
            return new EncryptedEmbedding(new byte[] {1}, new byte[] {2}, new byte[] {3}, "key_01", "AES-256-GCM", "hash_01");
        }

        @Override
        public float[] decrypt(String tenantId, EncryptedEmbedding payload) {
            return new float[0];
        }
    }
}
