package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.storage.GenericFileUploadApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AudioUploadStatus;
import com.meeting.api.client.storage.AbortGenericFileUploadCommand;
import com.meeting.api.client.storage.CompleteGenericFileUploadCommand;
import com.meeting.api.client.storage.CreateGenericFilePartCommand;
import com.meeting.api.client.storage.CreateGenericFileUploadCommand;
import com.meeting.api.domain.storage.GenericFileUploadPart;
import com.meeting.api.domain.storage.GenericFileUploadRepository;
import com.meeting.api.domain.storage.GenericFileUploadSession;
import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericFileUploadApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-27T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void createSessionRejectsDisallowedMimeWith415() {
        TestContext ctx = new TestContext();

        assertThatThrownBy(() -> ctx.service.createSession(createCommand("evil.exe", "application/x-msdownload")))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException app = (ApplicationException) ex;
                assertThat(app.errorCode()).isEqualTo(ErrorCode.FILE_MIME_NOT_ALLOWED);
                assertThat(app.httpStatus()).isEqualTo(415);
            });
    }

    @Test
    void createSessionAllowsDocumentsAndContractAudioTypes() {
        TestContext ctx = new TestContext();

        // Documents
        assertThat(ctx.service.createSession(createCommand("ref.pdf", "application/pdf")).uploadId()).startsWith("upl_");

        // All audio MIME types from public-api.yaml contracts
        assertThat(ctx.service.createSession(createCommand("voice.wav", "audio/wav")).contentType()).isEqualTo("audio/wav");
        assertThat(ctx.service.createSession(createCommand("voice.wav", "audio/x-wav")).contentType()).isEqualTo("audio/x-wav");
        assertThat(ctx.service.createSession(createCommand("voice.mp3", "audio/mpeg")).contentType()).isEqualTo("audio/mpeg");
        assertThat(ctx.service.createSession(createCommand("voice.mp4", "audio/mp4")).contentType()).isEqualTo("audio/mp4");
        assertThat(ctx.service.createSession(createCommand("voice.m4a", "audio/x-m4a")).contentType()).isEqualTo("audio/x-m4a");
        assertThat(ctx.service.createSession(createCommand("voice.webm", "audio/webm")).contentType()).isEqualTo("audio/webm");
        assertThat(ctx.service.createSession(createCommand("voice.ogg", "audio/ogg")).contentType()).isEqualTo("audio/ogg");
        assertThat(ctx.service.createSession(createCommand("voice.flac", "audio/flac")).contentType()).isEqualTo("audio/flac");
        assertThat(ctx.service.createSession(createCommand("voice.bin", "application/octet-stream")).contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void createSessionUsesIsolatedGenericFileObjectKeyPrefix() {
        TestContext ctx = new TestContext();

        var session = ctx.service.createSession(createCommand("ref.pdf", "application/pdf"));

        assertThat(session.objectKey())
            .startsWith("tenants/tenant_01/generic-files/" + session.uploadId() + "/")
            .endsWith("/1");
    }

    @Test
    void createSessionCoercesPartSizeToSinglePutLikeAudioUploads() {
        TestContext ctx = new TestContext();

        var session = ctx.service.createSession(createCommand("large.pdf", "application/pdf", 20 * 1024 * 1024L));

        assertThat(session.partSizeBytes()).isGreaterThanOrEqualTo((int) session.fileSizeBytes());
        assertThat(Math.ceil((double) session.fileSizeBytes() / (double) session.partSizeBytes())).isEqualTo(1.0);
    }

    @Test
    void createSessionReturnsInitialPresignedPartForSinglePutUpload() {
        TestContext ctx = new TestContext();

        var session = ctx.service.createSession(createCommand("ref.pdf", "application/pdf"));

        assertThat(session.parts()).singleElement()
            .satisfies(part -> {
                assertThat(part.partNumber()).isEqualTo(1);
                assertThat(part.partSha256()).isEqualTo(session.fileSha256());
                assertThat(part.sizeBytes()).isEqualTo(session.fileSizeBytes());
                assertThat(part.uploadUrl()).contains(session.objectKey());
                assertThat(part.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-05-27T02:15:00Z"));
                assertThat(part.headers()).containsEntry("Content-Type", "application/pdf");
            });
    }

    @Test
    void completeCreatesDurableTenantFile() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("ref.pdf", "application/pdf")).uploadId();
        ctx.service.createPart(partCommand(uploadId, 1, sha('a')));

        var completed = ctx.service.complete(completeCommand(uploadId));

        assertThat(completed.fileId()).startsWith("file_");
        assertThat(completed.sha256()).isEqualTo(sha('a'));
        assertThat(completed.sizeBytes()).isEqualTo(1024);
        assertThat(completed.contentType()).isEqualTo("application/pdf");
        assertThat(ctx.files.files).hasSize(1);
        MeetingFile file = ctx.files.files.get(0);
        assertThat(file.meetingId()).isNull();
        assertThat(file.fileType()).isEqualTo("GENERIC");
        assertThat(file.filePurpose()).isEqualTo("REFERENCE");
    }

    @Test
    void abortMarksSessionAndDeletesTemporaryObject() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("ref.pdf", "application/pdf")).uploadId();

        ctx.service.abort(abortCommand(uploadId));

        assertThat(ctx.uploads.sessions.get(uploadId).uploadStatus()).isEqualTo(AudioUploadStatus.ABORTED);
        assertThat(ctx.storage.deletedKeys).contains("tenants/tenant_01/generic-files/" + uploadId + "/1");
    }

    @Test
    void createPartRejectsPartNumbersBeyondSinglePutSession() {
        TestContext ctx = new TestContext();
        String uploadId = ctx.service.createSession(createCommand("ref.pdf", "application/pdf")).uploadId();

        assertThatThrownBy(() -> ctx.service.createPart(partCommand(uploadId, 2, sha('b'))))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException app = (ApplicationException) ex;
                assertThat(app.errorCode()).isEqualTo(ErrorCode.UPLOAD_INCOMPLETE_PARTS);
                assertThat(app.httpStatus()).isEqualTo(409);
            });
    }

    private static CreateGenericFileUploadCommand createCommand(String fileName, String contentType) {
        return createCommand(fileName, contentType, 1024);
    }

    private static CreateGenericFileUploadCommand createCommand(String fileName, String contentType, long fileSizeBytes) {
        return new CreateGenericFileUploadCommand(
            "tenant_01",
            fileName,
            contentType,
            fileSizeBytes,
            sha('a'),
            null,
            "user_01",
            "idem_01",
            "req_01",
            "trace_01"
        );
    }

    private static CreateGenericFilePartCommand partCommand(String uploadId, int partNumber, String partSha256) {
        return new CreateGenericFilePartCommand(
            "tenant_01",
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

    private static CompleteGenericFileUploadCommand completeCommand(String uploadId) {
        return new CompleteGenericFileUploadCommand(
            "tenant_01",
            uploadId,
            sha('a'),
            List.of(new CompleteGenericFileUploadCommand.PartCommand(1, sha('a'), "etag_01")),
            "user_01",
            "idem_complete",
            "req_01",
            "trace_01"
        );
    }

    private static AbortGenericFileUploadCommand abortCommand(String uploadId) {
        return new AbortGenericFileUploadCommand(
            "tenant_01",
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
        private final InMemoryGenericUploads uploads = new InMemoryGenericUploads();
        private final InMemoryMeetingFiles files = new InMemoryMeetingFiles();
        private final FakeStorage storage = new FakeStorage();
        private final GenericFileUploadApplicationService service = new GenericFileUploadApplicationService(
            uploads,
            files,
            storage,
            TenantScopedTransaction.immediate(),
            CLOCK
        );
    }

    private static final class InMemoryGenericUploads implements GenericFileUploadRepository {
        private final Map<String, GenericFileUploadSession> sessions = new HashMap<>();
        private final Map<String, GenericFileUploadPart> parts = new HashMap<>();

        @Override
        public GenericFileUploadSession saveSession(GenericFileUploadSession session) {
            sessions.put(session.uploadId(), session);
            return session;
        }

        @Override
        public GenericFileUploadPart savePart(GenericFileUploadPart part) {
            parts.put(key(part.uploadId(), part.partNumber()), part);
            return part;
        }

        @Override
        public Optional<GenericFileUploadSession> findSession(String tenantId, String uploadId) {
            GenericFileUploadSession session = sessions.get(uploadId);
            return session != null && tenantId.equals(session.tenantId()) ? Optional.of(session) : Optional.empty();
        }

        @Override
        public Optional<GenericFileUploadPart> findPart(String tenantId, String uploadId, int partNumber) {
            GenericFileUploadPart part = parts.get(key(uploadId, partNumber));
            return part != null && tenantId.equals(part.tenantId()) ? Optional.of(part) : Optional.empty();
        }

        @Override
        public List<GenericFileUploadPart> findParts(String tenantId, String uploadId) {
            return parts.values().stream()
                .filter(part -> tenantId.equals(part.tenantId()))
                .filter(part -> uploadId.equals(part.uploadId()))
                .sorted(Comparator.comparingInt(GenericFileUploadPart::partNumber))
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

        @Override
        public Optional<MeetingFile> findById(String tenantId, String fileId) {
            return files.stream()
                .filter(file -> tenantId.equals(file.tenantId()) && fileId.equals(file.fileId()))
                .findFirst();
        }
    }

    private static final class FakeStorage implements ObjectStorageGateway {
        private final List<String> deletedKeys = new ArrayList<>();

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
            deletedKeys.add(objectKey);
        }

        @Override
        public StorageObject putObject(String bucket, String objectKey, byte[] bytes, String contentType, String sha256) {
            return new StorageObject(bucket, objectKey, bytes.length, sha256, "etag_put", OffsetDateTime.now(CLOCK));
        }
    }
}
