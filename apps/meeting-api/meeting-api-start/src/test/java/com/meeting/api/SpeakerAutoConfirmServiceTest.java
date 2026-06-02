package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.speaker.MeetingSpeakerApplicationService;
import com.meeting.api.app.speaker.SpeakerAutoConfirmService;
import com.meeting.api.app.transcript.TranscriptApplicationService;
import com.meeting.api.client.enums.AuditActorType;
import com.meeting.api.client.enums.AuditResult;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.transcript.TranscriptRepository.TranscriptSegmentRecord;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeakerAutoConfirmServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-27T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-27T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void confirmsOnlyCandidateOrUnconfirmedSingleSafeMatchAboveThresholdAndAuditsReason() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speaker("SPEAKER_00", List.of("person_01"), 0.92, "CANDIDATE"),
            speaker("SPEAKER_01", List.of("person_02"), 0.84, "CANDIDATE"),
            speaker("SPEAKER_02", List.of("person_03", "person_04"), 0.99, "CANDIDATE"),
            speaker("SPEAKER_03", List.of("person_05"), 0.97, "CONFIRMED")
        ));
        CapturingAudit audit = new CapturingAudit();
        SpeakerAutoConfirmService service = service(speakers, audit);

        service.autoConfirmAboveThreshold("tenant_01", "task_01");

        assertThat(speakers.confirmedLabels).containsExactly("SPEAKER_00");
        assertThat(speakers.records.get(0).confirmedBy()).isEqualTo("auto-confirm@system");
        assertThat(audit.entries).hasSize(1);
        AuditEventLogger.AuditEntry entry = audit.entries.get(0);
        assertThat(entry.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(entry.reason()).isEqualTo("auto_confirm");
        assertThat(entry.payload()).containsEntry("confidence", 0.92);
    }

    @Test
    void confirmFailureDoesNotBlockOtherLabels() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speaker("SPEAKER_00", List.of("person_fail"), 0.91, "CANDIDATE"),
            speaker("SPEAKER_01", List.of("person_02"), 0.90, "CANDIDATE")
        ));
        SpeakerAutoConfirmService service = service(speakers, new CapturingAudit());

        service.autoConfirmAboveThreshold("tenant_01", "task_01");

        assertThat(speakers.confirmedLabels).containsExactly("SPEAKER_01");
    }

    @Test
    void autoConfirmUsesPersonDisplayNameForTranscriptAndSpeakerDtoWhenProfileIdAbsent() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speaker("SPEAKER_00", List.of("person_01"), 0.92, "CANDIDATE")
        ));
        CapturingTranscriptRepository transcriptRepository = new CapturingTranscriptRepository();
        MeetingSpeakerApplicationService confirmService = confirmService(
            speakers,
            transcriptRepository,
            new InMemoryPersons(List.of(new Person(
                "person_01",
                "tenant_01",
                "李四",
                "lisi@example.com",
                null,
                "ACTIVE",
                NOW
            )))
        );
        SpeakerAutoConfirmService service = service(speakers, confirmService, new CapturingAudit());

        service.autoConfirmAboveThreshold("tenant_01", "task_01");

        assertThat(transcriptRepository.lastDisplayName).isEqualTo("李四");
        assertThat(confirmService.list("tenant_01", "meeting_01"))
            .singleElement()
            .satisfies(dto -> {
                assertThat(dto.personId()).isEqualTo("person_01");
                assertThat(dto.displayName()).isEqualTo("李四");
                assertThat(dto.confirmationStatus()).isEqualTo("AUTO_CONFIRMED");
            });
    }

    @Test
    void autoConfirmRetainsSingleCandidateSpeakerProfileIdWhenAvailable() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speakerWithCandidates(
                "SPEAKER_00",
                List.of(new MeetingSpeakerRepository.SpeakerCandidate("person_01", "profile_01", 0.92)),
                0.92,
                "CANDIDATE"
            )
        ));
        CapturingTranscriptRepository transcriptRepository = new CapturingTranscriptRepository();
        MeetingSpeakerApplicationService confirmService = confirmService(
            speakers,
            new Profiles(List.of(SpeakerProfile.restore(
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
            ))),
            transcriptRepository,
            new InMemoryPersons(List.of())
        );
        SpeakerAutoConfirmService service = service(speakers, confirmService, new CapturingAudit());

        service.autoConfirmAboveThreshold("tenant_01", "task_01");

        assertThat(transcriptRepository.lastDisplayName).isEqualTo("Alice Profile");
        assertThat(confirmService.list("tenant_01", "meeting_01"))
            .singleElement()
            .satisfies(dto -> {
                assertThat(dto.personId()).isEqualTo("person_01");
                assertThat(dto.speakerProfileId()).isEqualTo("profile_01");
                assertThat(dto.confirmationStatus()).isEqualTo("AUTO_CONFIRMED");
            });
    }

    @Test
    void listExposesFullCandidatesWithProfileDisplayName() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speakerWithCandidates(
                "SPEAKER_00",
                List.of(new MeetingSpeakerRepository.SpeakerCandidate("person_01", "profile_01", 0.91)),
                0.91,
                "CANDIDATE"
            )
        ));
        MeetingSpeakerApplicationService confirmService = confirmService(
            speakers,
            new Profiles(List.of(SpeakerProfile.restore(
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
            ))),
            new InMemoryPersons(List.of(new Person(
                "person_01",
                "tenant_01",
                "Alice Person",
                "alice@example.com",
                null,
                "ACTIVE",
                NOW
            )))
        );

        assertThat(confirmService.list("tenant_01", "meeting_01"))
            .singleElement()
            .satisfies(dto -> assertThat(dto.candidates())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.personId()).isEqualTo("person_01");
                    assertThat(candidate.speakerProfileId()).isEqualTo("profile_01");
                    assertThat(candidate.displayName()).isEqualTo("Alice Profile");
                    assertThat(candidate.confidence()).isEqualTo(0.91);
                }));
    }

    @Test
    void humanConfirmRetainsChosenSpeakerProfileIdInSpeakerList() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speakerWithCandidates(
                "SPEAKER_00",
                List.of(new MeetingSpeakerRepository.SpeakerCandidate("person_01", "profile_01", 0.91)),
                0.91,
                "CANDIDATE"
            )
        ));
        MeetingSpeakerApplicationService confirmService = confirmService(
            speakers,
            new Profiles(List.of(SpeakerProfile.restore(
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
            ))),
            new InMemoryPersons(List.of())
        );

        confirmService.confirm(
            "tenant_01",
            "meeting_01",
            "SPEAKER_00",
            "person_01",
            "profile_01",
            1,
            "user_01"
        );

        assertThat(confirmService.list("tenant_01", "meeting_01"))
            .singleElement()
            .satisfies(dto -> {
                assertThat(dto.personId()).isEqualTo("person_01");
                assertThat(dto.speakerProfileId()).isEqualTo("profile_01");
                assertThat(dto.confirmationStatus()).isEqualTo("MANUALLY_CONFIRMED");
            });
    }

    @Test
    void listHidesStoredCandidateWhenProfileWasRevokedAfterCallback() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speakerWithCandidates(
                "SPEAKER_00",
                List.of(new MeetingSpeakerRepository.SpeakerCandidate("person_01", "profile_01", 0.91)),
                0.91,
                "CANDIDATE"
            )
        ));
        MeetingSpeakerApplicationService confirmService = confirmService(
            speakers,
            new Profiles(List.of(SpeakerProfile.restore(
                "profile_01",
                "tenant_01",
                "person_01",
                "Alice Profile",
                "REVOKED",
                "INVITE",
                "v1",
                "user_01",
                NOW.minusHours(1),
                null,
                NOW.minusDays(1),
                NOW.minusHours(1)
            ))),
            new InMemoryPersons(List.of())
        );

        assertThat(confirmService.list("tenant_01", "meeting_01"))
            .singleElement()
            .satisfies(dto -> assertThat(dto.candidates()).isEmpty());
    }

    @Test
    void humanConfirmRejectsStaleTranscriptVersionBeforeMutatingSpeakerOrTranscript() {
        InMemorySpeakers speakers = new InMemorySpeakers(List.of(
            speaker("SPEAKER_00", List.of("person_01"), 0.92, "CANDIDATE")
        ));
        CapturingTranscriptRepository transcriptRepository = new CapturingTranscriptRepository();
        transcriptRepository.currentVersion = 2;
        MeetingSpeakerApplicationService confirmService = confirmService(
            speakers,
            transcriptRepository,
            new InMemoryPersons(List.of(new Person(
                "person_01",
                "tenant_01",
                "李四",
                "lisi@example.com",
                null,
                "ACTIVE",
                NOW
            )))
        );

        assertThatThrownBy(() -> confirmService.confirm(
            "tenant_01",
            "meeting_01",
            "SPEAKER_00",
            "person_01",
            null,
            1,
            "user_01"
        )).isInstanceOf(TranscriptApplicationService.TranscriptVersionConflictException.class)
            .satisfies(ex -> {
                TranscriptApplicationService.TranscriptVersionConflictException conflict =
                    (TranscriptApplicationService.TranscriptVersionConflictException) ex;
                assertThat(conflict.actualVersion()).isEqualTo(2);
                assertThat(conflict.expectedVersion()).isEqualTo(1);
            });
        assertThat(speakers.confirmedLabels).isEmpty();
        assertThat(transcriptRepository.lastDisplayName).isNull();
    }

    private static SpeakerAutoConfirmService service(InMemorySpeakers speakers, AuditEventLogger audit) {
        return service(speakers, confirmService(speakers, new NoopTranscriptRepository(), new InMemoryPersons(List.of())), audit);
    }

    private static SpeakerAutoConfirmService service(
        InMemorySpeakers speakers,
        MeetingSpeakerApplicationService confirmService,
        AuditEventLogger audit
    ) {
        InMemoryTasks tasks = new InMemoryTasks();
        return new SpeakerAutoConfirmService(tasks, speakers, confirmService, audit);
    }

    private static MeetingSpeakerApplicationService confirmService(
        InMemorySpeakers speakers,
        TranscriptRepository transcriptRepository,
        PersonRepository personRepository
    ) {
        return confirmService(speakers, new EmptySpeakerProfiles(), transcriptRepository, personRepository);
    }

    private static MeetingSpeakerApplicationService confirmService(
        InMemorySpeakers speakers,
        SpeakerProfileRepository speakerProfileRepository,
        PersonRepository personRepository
    ) {
        return confirmService(speakers, speakerProfileRepository, new NoopTranscriptRepository(), personRepository);
    }

    private static MeetingSpeakerApplicationService confirmService(
        InMemorySpeakers speakers,
        SpeakerProfileRepository speakerProfileRepository,
        TranscriptRepository transcriptRepository,
        PersonRepository personRepository
    ) {
        return new MeetingSpeakerApplicationService(
            speakers,
            speakerProfileRepository,
            personRepository,
            transcriptRepository,
            new NoopKnowledgeChunkRepository(),
            TenantScopedTransaction.immediate(),
            CLOCK
        );
    }

    private static MeetingSpeakerRepository.MeetingSpeakerRecord speakerWithCandidates(
        String label,
        List<MeetingSpeakerRepository.SpeakerCandidate> candidates,
        Double score,
        String status
    ) {
        return new MeetingSpeakerRepository.MeetingSpeakerRecord(
            "ms_" + label,
            "tenant_01",
            "meeting_01",
            label,
            label,
            candidates.stream().map(MeetingSpeakerRepository.SpeakerCandidate::personId).toList(),
            candidates,
            score,
            "WORKER",
            status,
            null,
            null,
            null,
            null,
            NOW,
            NOW
        );
    }

    private static MeetingSpeakerRepository.MeetingSpeakerRecord speaker(
        String label,
        List<String> candidates,
        Double score,
        String status
    ) {
        return new MeetingSpeakerRepository.MeetingSpeakerRecord(
            "ms_" + label,
            "tenant_01",
            "meeting_01",
            label,
            label,
            candidates,
            score,
            "WORKER",
            status,
            null,
            null,
            null,
            null,
            NOW,
            NOW
        );
    }

    private static final class InMemoryTasks implements ProcessingTaskRepository {
        private final ProcessingTask task = ProcessingTask.create(
            "task_01",
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.SUMMARY, ProcessingStep.EXTRACTION),
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

    private static final class InMemorySpeakers implements MeetingSpeakerRepository {
        private final List<MeetingSpeakerRecord> records;
        private final List<String> confirmedLabels = new ArrayList<>();

        private InMemorySpeakers(List<MeetingSpeakerRecord> records) {
            this.records = new ArrayList<>(records);
        }

        @Override
        public Optional<MeetingSpeakerRecord> find(String tenantId, String meetingId, String speakerLabel) {
            return records.stream().filter(r -> r.speakerLabel().equals(speakerLabel)).findFirst();
        }

        @Override
        public List<MeetingSpeakerRecord> findByMeeting(String tenantId, String meetingId) {
            return records;
        }

        @Override
        public List<String> findMeetingIdsByConfirmedPerson(String tenantId, String personId) {
            return List.of();
        }

        @Override
        public void saveCandidates(String tenantId, String meetingId, String speakerLabel, List<String> candidatePersonIds,
                                   Double autoMatchScore, String matchSource, OffsetDateTime now) {
        }

        @Override
        public void confirm(String tenantId, String meetingId, String speakerLabel, String confirmedPersonId,
                            String confirmedSpeakerProfileId, String confirmedBy, OffsetDateTime now) {
            if ("person_fail".equals(confirmedPersonId)) {
                throw new RuntimeException("simulated confirm failure");
            }
            confirmedLabels.add(speakerLabel);
            for (int i = 0; i < records.size(); i++) {
                MeetingSpeakerRecord record = records.get(i);
                if (tenantId.equals(record.tenantId())
                    && meetingId.equals(record.meetingId())
                    && speakerLabel.equals(record.speakerLabel())) {
                    records.set(i, new MeetingSpeakerRecord(
                        record.id(),
                        record.tenantId(),
                        record.meetingId(),
                        record.speakerLabel(),
                        record.globalSpeakerLabel(),
                        record.candidatePersonIds(),
                        record.candidates(),
                        record.autoMatchScore(),
                        record.matchSource(),
                        "CONFIRMED",
                        confirmedPersonId,
                        confirmedSpeakerProfileId,
                        confirmedBy,
                        now,
                        record.createdAt(),
                        now
                    ));
                    return;
                }
            }
        }

        @Override
        public void reject(String tenantId, String meetingId, String speakerLabel, String rejectedBy, OffsetDateTime now) {
        }
    }

    private static final class CapturingAudit implements AuditEventLogger {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void log(AuditEntry entry) {
            entries.add(entry);
        }
    }

    private static final class EmptySpeakerProfiles implements SpeakerProfileRepository {
        @Override public SpeakerProfile save(SpeakerProfile profile) { return profile; }
        @Override public Optional<SpeakerProfile> findById(String tenantId, String profileId) { return Optional.empty(); }
        @Override public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) { return List.of(); }
        @Override public List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds) { return List.of(); }
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

    private static final class Profiles implements SpeakerProfileRepository {
        private final List<SpeakerProfile> profiles;

        private Profiles(List<SpeakerProfile> profiles) {
            this.profiles = profiles;
        }

        @Override public SpeakerProfile save(SpeakerProfile profile) { return profile; }

        @Override
        public Optional<SpeakerProfile> findById(String tenantId, String profileId) {
            return profiles.stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> profileId.equals(profile.id()))
                .findFirst();
        }

        @Override public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) { return List.of(); }

        @Override
        public List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds) {
            return profiles.stream()
                .filter(profile -> tenantId.equals(profile.tenantId()))
                .filter(profile -> profileIds.contains(profile.id()))
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

    private static class NoopTranscriptRepository implements TranscriptRepository {
        protected int currentVersion = 1;

        @Override public int currentTranscriptVersion(String tenantId, String meetingId) { return currentVersion; }
        @Override public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion) { return List.of(); }
        @Override public Optional<TranscriptSegmentRecord> findSegment(String tenantId, String meetingId, String segmentId, int transcriptVersion) {
            return Optional.empty();
        }
        @Override public void replaceTranscript(
            String tenantId,
            String meetingId,
            int transcriptVersion,
            String artifactManifestId,
            List<TranscriptSegmentRecord> segments
        ) {
        }
        @Override public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion) {
        }
        @Override public void applySegmentEdit(
            String tenantId,
            String meetingId,
            String segmentId,
            int expectedTranscriptVersion,
            String editedText,
            String changedBy,
            String editReason,
            OffsetDateTime now
        ) {
        }
    }

    private static final class CapturingTranscriptRepository extends NoopTranscriptRepository {
        private String lastDisplayName;

        @Override
        public int updateSpeakerForLabel(
            String tenantId,
            String meetingId,
            String speakerLabel,
            String personId,
            String displayName,
            OffsetDateTime now
        ) {
            this.lastDisplayName = displayName;
            return 1;
        }
    }

    private static final class InMemoryPersons implements PersonRepository {
        private final List<Person> persons;

        private InMemoryPersons(List<Person> persons) {
            this.persons = persons;
        }

        @Override
        public Person save(Person person) {
            return person;
        }

        @Override
        public Optional<Person> findById(String tenantId, String personId) {
            return persons.stream()
                .filter(person -> tenantId.equals(person.tenantId()))
                .filter(person -> personId.equals(person.id()))
                .findFirst();
        }

        @Override
        public List<Person> findByDisplayName(String tenantId, String displayName) {
            return List.of();
        }

        @Override
        public List<Person> searchByQuery(String tenantId, String q, int limit) {
            return List.of();
        }
    }

    private static final class NoopKnowledgeChunkRepository implements KnowledgeChunkRepository {
        @Override public int markStaleForMeeting(String tenantId, String meetingId) {
            return 0;
        }
    }
}
