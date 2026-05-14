package com.meeting.api.app.storage;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AudioUploadStatus;
import com.meeting.api.client.storage.AbortAudioUploadCommand;
import com.meeting.api.client.storage.AudioUploadFacade;
import com.meeting.api.client.storage.AudioUploadPartUploadDTO;
import com.meeting.api.client.storage.AudioUploadSessionDTO;
import com.meeting.api.client.storage.CompleteAudioUploadCommand;
import com.meeting.api.client.storage.CreateAudioUploadPartCommand;
import com.meeting.api.client.storage.CreateAudioUploadSessionCommand;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.storage.AudioUploadPart;
import com.meeting.api.domain.storage.AudioUploadRepository;
import com.meeting.api.domain.storage.AudioUploadSession;
import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AudioUploadApplicationService implements AudioUploadFacade {
    private static final int PRESIGNED_URL_TTL_MINUTES = 15;
    private static final String FILE_TYPE_AUDIO = "AUDIO";
    private static final String FILE_PURPOSE_RAW_AUDIO = "RAW_AUDIO";
    private static final String FILE_UPLOAD_STATUS_COMPLETED = "COMPLETED";

    private final MeetingRepository meetingRepository;
    private final AudioUploadRepository uploadRepository;
    private final MeetingFileRepository meetingFileRepository;
    private final ObjectStorageGateway objectStorageGateway;
    private final ProcessingTaskApplicationService processingTaskService;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    public AudioUploadApplicationService(
        MeetingRepository meetingRepository,
        AudioUploadRepository uploadRepository,
        MeetingFileRepository meetingFileRepository,
        ObjectStorageGateway objectStorageGateway,
        ProcessingTaskApplicationService processingTaskService,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(
            meetingRepository,
            uploadRepository,
            meetingFileRepository,
            objectStorageGateway,
            processingTaskService,
            tenantScopedTransaction,
            Clock.systemUTC()
        );
    }

    public AudioUploadApplicationService(
        MeetingRepository meetingRepository,
        AudioUploadRepository uploadRepository,
        MeetingFileRepository meetingFileRepository,
        ObjectStorageGateway objectStorageGateway,
        ProcessingTaskApplicationService processingTaskService,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.meetingRepository = meetingRepository;
        this.uploadRepository = uploadRepository;
        this.meetingFileRepository = meetingFileRepository;
        this.objectStorageGateway = objectStorageGateway;
        this.processingTaskService = processingTaskService;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public AudioUploadSessionDTO createSession(CreateAudioUploadSessionCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            requireMeeting(command.tenantId(), command.meetingId());
            validatePartSize(command.partSizeBytes());
            OffsetDateTime now = now();
            String uploadId = "upl_" + UUID.randomUUID().toString().replace("-", "");
            AudioUploadSession session = AudioUploadSession.create(
                uploadId,
                command.tenantId(),
                command.meetingId(),
                objectKey(command.tenantId(), command.meetingId(), uploadId),
                objectStorageGateway.defaultBucket(),
                command.contentType(),
                command.fileName(),
                command.fileSizeBytes(),
                command.fileSha256(),
                command.partSizeBytes(),
                command.requestedBy(),
                now
            );
            AudioUploadSession saved = uploadRepository.saveSession(session);
            return toDto(saved);
        });
    }

    @Override
    public AudioUploadPartUploadDTO createPart(CreateAudioUploadPartCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            AudioUploadSession session = requireActiveSession(command.tenantId(), command.meetingId(), command.uploadId());
            validatePartNumber(command.partNumber(), session.maxPartCount());
            if (command.sizeBytes() > session.partSizeBytes()) {
                throw validation("part size exceeds session partSizeBytes");
            }
            OffsetDateTime now = now();
            Optional<AudioUploadPart> existing = uploadRepository.findPart(command.tenantId(), command.uploadId(), command.partNumber());
            AudioUploadPart part;
            if (existing.isPresent()) {
                part = existing.get();
                if (!part.partSha256().equals(command.partSha256())) {
                    throw conflict(ErrorCode.UPLOAD_PART_HASH_MISMATCH, "part sha256 does not match existing part");
                }
            } else {
                part = uploadRepository.savePart(AudioUploadPart.requested(
                    "aup_" + UUID.randomUUID().toString().replace("-", ""),
                    command.tenantId(),
                    command.uploadId(),
                    command.meetingId(),
                    command.partNumber(),
                    command.partSha256(),
                    command.sizeBytes(),
                    now
                ));
            }
            if (session.uploadStatus() == AudioUploadStatus.INITIATED) {
                uploadRepository.saveSession(session.markUploading(now));
            }
            ObjectStorageGateway.PresignedUrl presigned = objectStorageGateway.presignPut(
                session.bucket(),
                session.objectKey(),
                command.partNumber(),
                session.contentType(),
                now.plusMinutes(PRESIGNED_URL_TTL_MINUTES)
            );
            return new AudioUploadPartUploadDTO(
                session.uploadId(),
                part.partNumber(),
                part.partSha256(),
                part.etag(),
                presigned.url(),
                presigned.expiresAt(),
                presigned.headers()
            );
        });
    }

    @Override
    public AudioUploadSessionDTO complete(CompleteAudioUploadCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            AudioUploadSession session = requireSession(command.tenantId(), command.meetingId(), command.uploadId());
            if (session.uploadStatus() == AudioUploadStatus.COMPLETED) {
                return toDto(session);
            }
            ensureMutable(session);
            if (!session.fileSha256().equals(command.fileSha256())) {
                throw conflict(ErrorCode.UPLOAD_FILE_HASH_MISMATCH, "file sha256 does not match upload session");
            }
            List<CompleteAudioUploadCommand.PartCommand> requestedParts = command.parts() == null ? List.of() : command.parts();
            if (requestedParts.isEmpty()) {
                throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "complete requires at least one part");
            }
            if (requestedParts.size() > session.maxPartCount()) {
                throw validation(ErrorCode.UPLOAD_TOO_MANY_PARTS, "too many upload parts");
            }
            validateExpectedPartSet(session, requestedParts);
            List<AudioUploadPart> savedParts = uploadRepository.findParts(command.tenantId(), command.uploadId());
            Map<Integer, AudioUploadPart> savedByNumber = savedParts.stream()
                .collect(java.util.stream.Collectors.toMap(AudioUploadPart::partNumber, part -> part));
            OffsetDateTime now = now();
            for (CompleteAudioUploadCommand.PartCommand requestedPart : requestedParts) {
                AudioUploadPart savedPart = savedByNumber.get(requestedPart.partNumber());
                if (savedPart == null) {
                    throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "part is missing: " + requestedPart.partNumber());
                }
                if (!savedPart.partSha256().equals(requestedPart.partSha256())) {
                    throw conflict(ErrorCode.UPLOAD_PART_HASH_MISMATCH, "part sha256 mismatch: " + requestedPart.partNumber());
                }
                if (requestedPart.etag() == null || requestedPart.etag().isBlank()) {
                    throw validation("etag must not be blank");
                }
                if (savedPart.uploadStatus() != AudioUploadStatus.COMPLETED || savedPart.etag() == null) {
                    uploadRepository.savePart(savedPart.markCompleted(requestedPart.etag(), now));
                }
            }
            StorageObject object = objectStorageGateway.statObject(session.bucket(), session.objectKey());
            if (object.sha256() != null && !object.sha256().isBlank() && !object.sha256().equals(session.fileSha256())) {
                throw conflict(ErrorCode.UPLOAD_FILE_HASH_MISMATCH, "stored object sha256 mismatch");
            }
            MeetingFile file = meetingFileRepository.save(new MeetingFile(
                "file_" + UUID.randomUUID().toString().replace("-", ""),
                session.tenantId(),
                session.meetingId(),
                FILE_TYPE_AUDIO,
                FILE_PURPOSE_RAW_AUDIO,
                session.fileName(),
                session.contentType(),
                session.bucket(),
                session.objectKey(),
                storageUri(session.bucket(), session.objectKey()),
                object.sizeBytes() > 0 ? object.sizeBytes() : session.fileSizeBytes(),
                session.fileSha256(),
                command.durationMs(),
                FILE_UPLOAD_STATUS_COMPLETED,
                session.createdBy(),
                now,
                now
            ));
            AudioUploadSession completed = uploadRepository.saveSession(session.markCompleted(file.fileId(), now));
            processingTaskService.createForCompletedAudioUpload(
                completed.tenantId(),
                completed.meetingId(),
                file.fileId(),
                storageUri(completed.bucket(), completed.objectKey()),
                completed.bucket(),
                completed.objectKey(),
                completed.fileSha256(),
                completed.fileSizeBytes(),
                command.requestedBy(),
                command.idempotencyKey(),
                command.requestId(),
                command.traceId()
            );
            return toDto(completed);
        });
    }

    @Override
    public AudioUploadSessionDTO abort(AbortAudioUploadCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            AudioUploadSession session = requireSession(command.tenantId(), command.meetingId(), command.uploadId());
            if (session.uploadStatus() == AudioUploadStatus.COMPLETED) {
                throw conflict(ErrorCode.UPLOAD_ALREADY_COMPLETED, "completed upload cannot be aborted");
            }
            if (session.uploadStatus() != AudioUploadStatus.ABORTED) {
                try {
                    objectStorageGateway.deleteObject(session.bucket(), session.objectKey());
                } catch (RuntimeException ignored) {
                    // Abort is best-effort for temporary objects; session state is authoritative.
                }
                session = uploadRepository.saveSession(session.markAborted(now()));
            }
            return toDto(session);
        });
    }

    @Override
    public Optional<AudioUploadSessionDTO> get(String tenantId, String meetingId, String uploadId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () ->
            uploadRepository.findSession(tenantId, uploadId)
                .filter(session -> session.meetingId().equals(meetingId))
                .map(this::expireIfNeeded)
                .map(this::toDto)
        );
    }

    private AudioUploadSession requireActiveSession(String tenantId, String meetingId, String uploadId) {
        AudioUploadSession session = requireSession(tenantId, meetingId, uploadId);
        ensureMutable(session);
        return session;
    }

    private AudioUploadSession requireSession(String tenantId, String meetingId, String uploadId) {
        AudioUploadSession session = uploadRepository.findSession(tenantId, uploadId)
            .orElseThrow(() -> notFound(ErrorCode.VALIDATION_FAILED, "upload session not found: " + uploadId));
        if (!meetingId.equals(session.meetingId())) {
            throw forbidden(ErrorCode.PERMISSION_DENIED, "upload session is not accessible in current tenant");
        }
        return expireIfNeeded(session);
    }

    private AudioUploadSession expireIfNeeded(AudioUploadSession session) {
        if (!session.isExpired(now())) {
            return session;
        }
        return uploadRepository.saveSession(session.markExpired(now()));
    }

    private void ensureMutable(AudioUploadSession session) {
        if (session.uploadStatus() == AudioUploadStatus.EXPIRED) {
            throw gone(ErrorCode.UPLOAD_SESSION_EXPIRED, "upload session expired");
        }
        if (session.isExpired(now())) {
            throw gone(ErrorCode.UPLOAD_SESSION_EXPIRED, "upload session expired");
        }
        if (session.uploadStatus() == AudioUploadStatus.ABORTED) {
            throw conflict(ErrorCode.UPLOAD_ALREADY_ABORTED, "upload session already aborted");
        }
        if (session.uploadStatus() == AudioUploadStatus.COMPLETED) {
            throw conflict(ErrorCode.UPLOAD_ALREADY_COMPLETED, "upload session already completed");
        }
    }

    private Meeting requireMeeting(String tenantId, String meetingId) {
        return meetingRepository.findById(tenantId, meetingId)
            .orElseThrow(() -> notFound(ErrorCode.VALIDATION_FAILED, "meeting not found: " + meetingId));
    }

    private AudioUploadSessionDTO toDto(AudioUploadSession session) {
        List<AudioUploadPart> parts = uploadRepository.findParts(session.tenantId(), session.uploadId()).stream()
            .sorted(Comparator.comparingInt(AudioUploadPart::partNumber))
            .toList();
        return AudioUploadAssembler.toDto(session, parts);
    }

    private static void validatePartSize(Integer partSizeBytes) {
        if (partSizeBytes != null && partSizeBytes < 5 * 1024 * 1024) {
            throw validation("partSizeBytes must be at least 5 MiB");
        }
    }

    private static void validatePartNumber(int partNumber, int maxPartCount) {
        if (partNumber < 1 || partNumber > maxPartCount) {
            throw validation("partNumber must be between 1 and " + maxPartCount);
        }
    }

    private static void validateExpectedPartSet(
        AudioUploadSession session,
        List<CompleteAudioUploadCommand.PartCommand> requestedParts
    ) {
        int expectedPartCount = (int) Math.ceil((double) session.fileSizeBytes() / (double) session.partSizeBytes());
        if (requestedParts.size() != expectedPartCount) {
            throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "complete parts do not match expected part count");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (CompleteAudioUploadCommand.PartCommand part : requestedParts) {
            if (!seen.add(part.partNumber())) {
                throw validation("duplicate partNumber: " + part.partNumber());
            }
            if (part.partNumber() < 1 || part.partNumber() > expectedPartCount) {
                throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "partNumber outside expected complete range");
            }
        }
    }

    private static String objectKey(String tenantId, String meetingId, String uploadId) {
        return "meeting-audio/" + tenantId + "/" + meetingId + "/" + uploadId + "/raw";
    }

    private static String storageUri(String bucket, String objectKey) {
        return "tos://" + bucket + "/" + objectKey;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static ApplicationException validation(String message) {
        return validation(ErrorCode.VALIDATION_FAILED, message);
    }

    private static ApplicationException validation(ErrorCode errorCode, String message) {
        return new ApplicationException(errorCode, 422, message, false);
    }

    private static ApplicationException conflict(ErrorCode errorCode, String message) {
        return new ApplicationException(errorCode, 409, message, false);
    }

    private static ApplicationException gone(ErrorCode errorCode, String message) {
        return new ApplicationException(errorCode, 410, message, false);
    }

    private static ApplicationException forbidden(ErrorCode errorCode, String message) {
        return new ApplicationException(errorCode, 403, message, false);
    }

    private static ApplicationException notFound(ErrorCode errorCode, String message) {
        return new ApplicationException(errorCode, 404, message, false);
    }
}
