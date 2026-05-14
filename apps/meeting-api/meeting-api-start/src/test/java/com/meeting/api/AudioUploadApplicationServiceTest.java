package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.storage.AudioUploadApplicationService;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AudioUploadStatus;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.storage.AbortAudioUploadCommand;
import com.meeting.api.client.storage.CompleteAudioUploadCommand;
import com.meeting.api.client.storage.CreateAudioUploadPartCommand;
import com.meeting.api.client.storage.CreateAudioUploadSessionCommand;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.storage.AudioUploadPart;
import com.meeting.api.domain.storage.AudioUploadRepository;
import com.meeting.api.domain.storage.AudioUploadSession;
import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static com.meeting.api.client.enums.SecurityLevel.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioUploadApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-14T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void createSessionUsesDefaultPolicyAndGeneratedObjectKey() {
        TestContext ctx = new TestContext();

        var dto = ctx.service.createSession(createCommand("meeting_01"));

        assertThat(dto.uploadStatus()).isEqualTo(AudioUploadStatus.INITIATED);
        assertThat(dto.partSizeBytes()).isEqualTo(8388608);
        assertThat(dto.maxPartCount()).isEqualTo(10000);
        assertThat(dto.objectKey()).startsWith("meeting-audio/tenant_01/meeting_01/upl_");
        assertThat(dto.objectKey()).endsWith("/raw");
        assertThat(dto.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-05-15T02:00:00Z"));
    }

    @Test
    void createSessionRejectsTenantMismatch() {
        TestContext ctx = new TestContext();

        assertThatThrownBy(() -> ctx.service.createSession(createCommand("meeting_other")))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createSessionReturnsNotFoundForMissingMeeting() {
        TestContext ctx = new TestContext();

        assertThatThrownBy(() -> ctx.service.createSession(createCommand("meeting_other")))
            .isInstanceOf(ApplicationException.class)
            .extracting("httpStatus")
            .isEqualTo(404);
    }

    @Test
    void createPartIsIdempotentForSameHashAndRejectsDifferentHash() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("meeting_01")).uploadId();

        var first = ctx.service.createPart(partCommand(uploadId, 1, sha('b')));
        var replay = ctx.service.createPart(partCommand(uploadId, 1, sha('b')));

        assertThat(replay.uploadUrl()).isEqualTo(first.uploadUrl());
        assertThatThrownBy(() -> ctx.service.createPart(partCommand(uploadId, 1, sha('c'))))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UPLOAD_PART_HASH_MISMATCH);
    }

    @Test
    void expiredUploadFailsForPartCreation() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("meeting_01")).uploadId();
        AudioUploadSession session = ctx.uploads.sessions.get(uploadId);
        ctx.uploads.saveSession(session.markExpired(OffsetDateTime.parse("2026-05-15T02:01:00Z")));

        assertThatThrownBy(() -> ctx.service.createPart(partCommand(uploadId, 1, sha('b'))))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UPLOAD_SESSION_EXPIRED);
        assertThatThrownBy(() -> ctx.service.createPart(partCommand(uploadId, 1, sha('b'))))
            .isInstanceOf(ApplicationException.class)
            .extracting("httpStatus")
            .isEqualTo(410);
    }

    @Test
    void abortIsIdempotentButCompletedUploadCannotAbort() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("meeting_01")).uploadId();

        var aborted = ctx.service.abort(abortCommand(uploadId));
        var replay = ctx.service.abort(abortCommand(uploadId));

        assertThat(aborted.uploadStatus()).isEqualTo(AudioUploadStatus.ABORTED);
        assertThat(replay.uploadStatus()).isEqualTo(AudioUploadStatus.ABORTED);

        String completedUploadId = ctx.service.createSession(createCommand("meeting_01")).uploadId();
        ctx.service.createPart(partCommand(completedUploadId, 1, sha('b')));
        ctx.service.complete(completeCommand(completedUploadId));

        assertThatThrownBy(() -> ctx.service.abort(abortCommand(completedUploadId)))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UPLOAD_ALREADY_COMPLETED);
    }

    @Test
    void completeCreatesMeetingFileTaskOutboxAndMovesMeetingToProcessing() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("meeting_01")).uploadId();
        ctx.service.createPart(partCommand(uploadId, 1, sha('b')));

        var completed = ctx.service.complete(completeCommand(uploadId));

        assertThat(completed.uploadStatus()).isEqualTo(AudioUploadStatus.COMPLETED);
        assertThat(completed.fileId()).startsWith("file_");
        assertThat(ctx.files.files).hasSize(1);
        assertThat(ctx.tasks.task).isNotNull();
        assertThat(ctx.meetings.meeting.status()).isEqualTo(MeetingStatus.PROCESSING);
        ProcessingTaskCreatedEvent event = (ProcessingTaskCreatedEvent) ctx.publisher.events.get(0);
        assertThat(event.pipelineSteps())
            .extracting(Enum::name)
            .containsExactly("AUDIO_PREPROCESS", "ASR", "DIARIZATION", "TRANSCRIPT_MERGE");
        assertThat(event.payload().get("audioFileId")).isEqualTo(completed.fileId());
        assertThat(event.payload().get("audioUri")).asString().startsWith("tos://meeting-local/");
        assertThat(event.payload().get("language")).isEqualTo("zh");
        assertThat(event.payload().get("minSpeakers")).isEqualTo(1);
        assertThat(event.payload().get("maxSpeakers")).isEqualTo(4);
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) event.payload().get("options");
        assertThat(options.get("inputAudioSha256")).isEqualTo(completed.fileSha256());
    }

    @Test
    void completeReplayReturnsExistingResult() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("meeting_01")).uploadId();
        ctx.service.createPart(partCommand(uploadId, 1, sha('b')));
        ctx.service.complete(completeCommand(uploadId));

        var replay = ctx.service.complete(completeCommand(uploadId));

        assertThat(replay.uploadStatus()).isEqualTo(AudioUploadStatus.COMPLETED);
        assertThat(ctx.publisher.events).hasSize(1);
    }

    private static CreateAudioUploadSessionCommand createCommand(String meetingId) {
        return new CreateAudioUploadSessionCommand(
            "tenant_01",
            meetingId,
            "standup.wav",
            "audio/wav",
            1024,
            sha('a'),
            null,
            "user_01",
            "idem_01",
            "req_01",
            "trace_01"
        );
    }

    private static CreateAudioUploadPartCommand partCommand(String uploadId, int partNumber, String partSha256) {
        return new CreateAudioUploadPartCommand(
            "tenant_01",
            "meeting_01",
            uploadId,
            partNumber,
            1024,
            partSha256,
            "user_01",
            "idem_part_" + partNumber,
            "req_01",
            "trace_01"
        );
    }

    private static CompleteAudioUploadCommand completeCommand(String uploadId) {
        return new CompleteAudioUploadCommand(
            "tenant_01",
            "meeting_01",
            uploadId,
            sha('a'),
            1000L,
            List.of(new CompleteAudioUploadCommand.PartCommand(1, sha('b'), "etag_01")),
            "user_01",
            "idem_complete",
            "req_01",
            "trace_01"
        );
    }

    private static AbortAudioUploadCommand abortCommand(String uploadId) {
        return new AbortAudioUploadCommand(
            "tenant_01",
            "meeting_01",
            uploadId,
            "user_01",
            "idem_abort",
            "req_01",
            "trace_01"
        );
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static final class TestContext {
        private final InMemoryMeetings meetings = new InMemoryMeetings();
        private final InMemoryUploads uploads = new InMemoryUploads();
        private final InMemoryMeetingFiles files = new InMemoryMeetingFiles();
        private final FakeStorage storage = new FakeStorage();
        private final InMemoryTasks tasks = new InMemoryTasks();
        private final CapturingPublisher publisher = new CapturingPublisher();
        private final ProcessingTaskApplicationService taskService = new ProcessingTaskApplicationService(
            tasks,
            meetings,
            publisher,
            TenantScopedTransaction.immediate(),
            CLOCK
        );
        private final AudioUploadApplicationService service = new AudioUploadApplicationService(
            meetings,
            uploads,
            files,
            storage,
            taskService,
            TenantScopedTransaction.immediate(),
            CLOCK
        );
    }

    private static final class InMemoryMeetings implements MeetingRepository {
        private Meeting meeting = Meeting.create("meeting_01", "tenant_01", "Weekly", INTERNAL, "zh", List.of(), "user_01");

        @Override
        public Meeting save(Meeting meeting) {
            this.meeting = meeting;
            return meeting;
        }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return tenantId.equals(meeting.tenantId()) && meetingId.equals(meeting.id()) ? Optional.of(meeting) : Optional.empty();
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return tenantId.equals(meeting.tenantId()) ? List.of(meeting) : List.of();
        }

        @Override
        public void updateStatus(String tenantId, String meetingId, MeetingStatus status) {
            if (tenantId.equals(meeting.tenantId()) && meetingId.equals(meeting.id())) {
                meeting = new Meeting.Builder()
                    .id(meeting.id())
                    .tenantId(meeting.tenantId())
                    .title(meeting.title())
                    .securityLevel(meeting.securityLevel())
                    .status(status)
                    .language(meeting.language())
                    .transcriptVersion(meeting.transcriptVersion())
                    .minutesVersion(meeting.minutesVersion())
                    .createdAt(meeting.createdAt())
                    .createdBy(meeting.createdBy())
                    .participants(meeting.participants())
                    .build();
            }
        }
    }

    private static final class InMemoryUploads implements AudioUploadRepository {
        private final Map<String, AudioUploadSession> sessions = new HashMap<>();
        private final Map<String, AudioUploadPart> parts = new HashMap<>();

        @Override
        public AudioUploadSession saveSession(AudioUploadSession session) {
            sessions.put(session.uploadId(), session);
            return session;
        }

        @Override
        public AudioUploadPart savePart(AudioUploadPart part) {
            parts.put(key(part.uploadId(), part.partNumber()), part);
            return part;
        }

        @Override
        public Optional<AudioUploadSession> findSession(String tenantId, String uploadId) {
            AudioUploadSession session = sessions.get(uploadId);
            return session != null && tenantId.equals(session.tenantId()) ? Optional.of(session) : Optional.empty();
        }

        @Override
        public Optional<AudioUploadPart> findPart(String tenantId, String uploadId, int partNumber) {
            AudioUploadPart part = parts.get(key(uploadId, partNumber));
            return part != null && tenantId.equals(part.tenantId()) ? Optional.of(part) : Optional.empty();
        }

        @Override
        public List<AudioUploadPart> findParts(String tenantId, String uploadId) {
            return parts.values().stream()
                .filter(part -> tenantId.equals(part.tenantId()))
                .filter(part -> uploadId.equals(part.uploadId()))
                .sorted(Comparator.comparingInt(AudioUploadPart::partNumber))
                .toList();
        }

        private static String key(String uploadId, int partNumber) {
            return uploadId + ":" + partNumber;
        }
    }

    private static final class InMemoryMeetingFiles implements MeetingFileRepository {
        private final List<MeetingFile> files = new ArrayList<>();

        @Override
        public MeetingFile save(MeetingFile file) {
            files.add(file);
            return file;
        }
    }

    private static final class FakeStorage implements ObjectStorageGateway {
        @Override
        public String defaultBucket() {
            return "meeting-local";
        }

        @Override
        public PresignedUrl presignPut(String bucket, String objectKey, int partNumber, String contentType, OffsetDateTime expiresAt) {
            return new PresignedUrl(
                "http://localhost:9000/" + bucket + "/" + objectKey + "?partNumber=" + partNumber,
                expiresAt,
                Map.of("Content-Type", contentType)
            );
        }

        @Override
        public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
            return new PresignedUrl("http://localhost:9000/" + bucket + "/" + objectKey, expiresAt, Map.of());
        }

        @Override
        public StorageObject statObject(String bucket, String objectKey) {
            return new StorageObject(bucket, objectKey, 1024, sha('a'), "etag_object", OffsetDateTime.now(CLOCK));
        }

        @Override
        public void deleteObject(String bucket, String objectKey) {
        }
    }

    private static final class InMemoryTasks implements ProcessingTaskRepository {
        private ProcessingTask task;

        @Override
        public ProcessingTask save(ProcessingTask task) {
            this.task = task;
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return task != null && tenantId.equals(task.tenantId()) && taskId.equals(task.taskId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return task != null && tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId()) ? Optional.of(task) : Optional.empty();
        }
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }
}
