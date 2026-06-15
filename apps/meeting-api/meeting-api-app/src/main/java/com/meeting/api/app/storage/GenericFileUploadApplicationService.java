package com.meeting.api.app.storage;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AudioUploadStatus;
import com.meeting.api.client.storage.AbortGenericFileUploadCommand;
import com.meeting.api.client.storage.CompleteGenericFileUploadCommand;
import com.meeting.api.client.storage.CreateGenericFilePartCommand;
import com.meeting.api.client.storage.CreateGenericFileUploadCommand;
import com.meeting.api.client.storage.GenericFileCompleteDTO;
import com.meeting.api.client.storage.GenericFileFacade;
import com.meeting.api.client.storage.GenericFileUploadPartDTO;
import com.meeting.api.client.storage.GenericFileUploadSessionDTO;
import com.meeting.api.domain.storage.GenericFileUploadPart;
import com.meeting.api.domain.storage.GenericFileUploadRepository;
import com.meeting.api.domain.storage.GenericFileUploadSession;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GenericFileUploadApplicationService implements GenericFileFacade {
    private static final int PRESIGNED_URL_TTL_MINUTES = 15;
    private static final int DEFAULT_PART_SIZE_BYTES = 8 * 1024 * 1024;
    private static final int MIN_PART_SIZE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 524_288_000L;
    private static final String FILE_TYPE_GENERIC = "GENERIC";
    private static final String FILE_PURPOSE_REFERENCE = "REFERENCE";
    private static final String FILE_UPLOAD_STATUS_COMPLETED = "COMPLETED";
    private static final Set<String> MIME_WHITELIST = Set.of(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "text/markdown",
        // Audio MIME types (must match public-api.yaml)
        "audio/wav",
        "audio/x-wav",
        "audio/mpeg",
        "audio/mp4",
        "audio/x-m4a",
        "audio/webm",
        "audio/ogg",
        "audio/flac",
        "application/octet-stream"
    );

    private final GenericFileUploadRepository uploadRepository;
    private final MeetingFileRepository meetingFileRepository;
    private final ObjectStorageGateway objectStorageGateway;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    @Autowired
    public GenericFileUploadApplicationService(
        GenericFileUploadRepository uploadRepository,
        MeetingFileRepository meetingFileRepository,
        ObjectStorageGateway objectStorageGateway,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(uploadRepository, meetingFileRepository, objectStorageGateway, tenantScopedTransaction, Clock.systemUTC());
    }

    public GenericFileUploadApplicationService(
        GenericFileUploadRepository uploadRepository,
        MeetingFileRepository meetingFileRepository,
        ObjectStorageGateway objectStorageGateway,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.uploadRepository = uploadRepository;
        this.meetingFileRepository = meetingFileRepository;
        this.objectStorageGateway = objectStorageGateway;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public GenericFileUploadSessionDTO createSession(CreateGenericFileUploadCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            validateMime(command.contentType());
            validateFileSize(command.fileSizeBytes());
            int effectivePartSize = resolvePartSize(command.fileSizeBytes(), command.partSizeBytes());
            OffsetDateTime now = now();
            String uploadId = "upl_" + UUID.randomUUID().toString().replace("-", "");
            GenericFileUploadSession session = GenericFileUploadSession.create(
                uploadId,
                command.tenantId(),
                objectKey(command.tenantId(), uploadId, command.fileName()),
                objectStorageGateway.defaultBucket(),
                command.contentType(),
                command.fileName(),
                command.fileSizeBytes(),
                command.fileSha256(),
                effectivePartSize,
                command.requestedBy(),
                now
            );
            GenericFileUploadSession saved = uploadRepository.saveSession(session);
            GenericFileUploadPart part = uploadRepository.savePart(GenericFileUploadPart.requested(
                "gup_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                uploadId,
                1,
                command.fileSha256(),
                command.fileSizeBytes(),
                now
            ));
            ObjectStorageGateway.PresignedUrl presigned = objectStorageGateway.presignPut(
                saved.bucket(),
                saved.objectKey(),
                part.partNumber(),
                saved.contentType(),
                now.plusMinutes(PRESIGNED_URL_TTL_MINUTES)
            );
            return toDto(saved, part, presigned);
        });
    }

    @Override
    public GenericFileUploadPartDTO createPart(CreateGenericFilePartCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            GenericFileUploadSession session = requireActiveSession(command.tenantId(), command.uploadId());
            validatePartNumber(command.partNumber(), session.maxPartCount());
            if (command.partNumber() > expectedPartCount(session)) {
                throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "partNumber outside expected upload range");
            }
            if (command.sizeBytes() > session.partSizeBytes()) {
                throw validation("part size exceeds session partSizeBytes");
            }
            OffsetDateTime now = now();
            Optional<GenericFileUploadPart> existing = uploadRepository.findPart(
                command.tenantId(), command.uploadId(), command.partNumber()
            );
            GenericFileUploadPart part;
            if (existing.isPresent()) {
                part = existing.get();
                if (!part.partSha256().equals(command.partSha256())) {
                    throw conflict(ErrorCode.UPLOAD_PART_HASH_MISMATCH, "part sha256 does not match existing part");
                }
            } else {
                part = uploadRepository.savePart(GenericFileUploadPart.requested(
                    "gup_" + UUID.randomUUID().toString().replace("-", ""),
                    command.tenantId(),
                    command.uploadId(),
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
            return new GenericFileUploadPartDTO(
                part.partNumber(),
                part.partSha256(),
                part.sizeBytes(),
                part.etag(),
                presigned.url(),
                presigned.expiresAt(),
                presigned.headers()
            );
        });
    }

    @Override
    public GenericFileCompleteDTO complete(CompleteGenericFileUploadCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            GenericFileUploadSession session = requireSession(command.tenantId(), command.uploadId());
            if (session.uploadStatus() == AudioUploadStatus.COMPLETED && session.fileId() != null) {
                return toCompleteDto(session);
            }
            ensureMutable(session);
            if (!session.fileSha256().equals(command.fileSha256())) {
                throw conflict(ErrorCode.UPLOAD_FILE_HASH_MISMATCH, "file sha256 does not match upload session");
            }
            List<CompleteGenericFileUploadCommand.PartCommand> requestedParts =
                command.parts() == null ? List.of() : command.parts();
            if (requestedParts.isEmpty()) {
                throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "complete requires at least one part");
            }
            if (requestedParts.size() > session.maxPartCount()) {
                throw validation(ErrorCode.UPLOAD_TOO_MANY_PARTS, "too many upload parts");
            }
            validateExpectedPartSet(session, requestedParts);
            Map<Integer, GenericFileUploadPart> savedByNumber = uploadRepository.findParts(command.tenantId(), command.uploadId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(GenericFileUploadPart::partNumber, part -> part));
            OffsetDateTime now = now();
            for (CompleteGenericFileUploadCommand.PartCommand requestedPart : requestedParts) {
                GenericFileUploadPart savedPart = savedByNumber.get(requestedPart.partNumber());
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
            if (object.sizeBytes() >= 0 && object.sizeBytes() != session.fileSizeBytes()) {
                throw conflict(
                    ErrorCode.UPLOAD_FILE_SIZE_MISMATCH,
                    "stored object size mismatch: stored=" + object.sizeBytes()
                        + " expected=" + session.fileSizeBytes()
                );
            }
            if (object.sha256() != null && !object.sha256().isBlank() && !object.sha256().equals(session.fileSha256())) {
                throw conflict(ErrorCode.UPLOAD_FILE_HASH_MISMATCH, "stored object sha256 mismatch");
            }
            MeetingFile file = meetingFileRepository.save(new MeetingFile(
                "file_" + UUID.randomUUID().toString().replace("-", ""),
                session.tenantId(),
                null,
                FILE_TYPE_GENERIC,
                FILE_PURPOSE_REFERENCE,
                session.fileName(),
                session.contentType(),
                session.bucket(),
                session.objectKey(),
                storageUri(session.bucket(), session.objectKey()),
                object.sizeBytes() >= 0 ? object.sizeBytes() : session.fileSizeBytes(),
                session.fileSha256(),
                null,
                FILE_UPLOAD_STATUS_COMPLETED,
                session.createdBy(),
                now,
                now
            ));
            GenericFileUploadSession completed = uploadRepository.saveSession(session.markCompleted(file.fileId(), now));
            return new GenericFileCompleteDTO(
                file.fileId(),
                completed.fileSha256(),
                file.sizeBytes(),
                completed.contentType()
            );
        });
    }

    @Override
    public void abort(AbortGenericFileUploadCommand command) {
        tenantScopedTransaction.executeWithoutResult(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            GenericFileUploadSession session = requireSession(command.tenantId(), command.uploadId());
            if (session.uploadStatus() == AudioUploadStatus.COMPLETED) {
                throw conflict(ErrorCode.UPLOAD_ALREADY_COMPLETED, "completed upload cannot be aborted");
            }
            if (session.uploadStatus() != AudioUploadStatus.ABORTED) {
                try {
                    objectStorageGateway.deleteObject(session.bucket(), session.objectKey());
                } catch (RuntimeException ignored) {
                    // Abort is best-effort for temporary objects; session state is authoritative.
                }
                uploadRepository.saveSession(session.markAborted(now()));
            }
        });
    }

    @Override
    public Optional<GenericFileUploadSessionDTO> get(String tenantId, String uploadId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () ->
            uploadRepository.findSession(tenantId, uploadId)
                .map(this::expireIfNeeded)
                .map(this::toDto)
        );
    }

    private GenericFileUploadSession requireActiveSession(String tenantId, String uploadId) {
        GenericFileUploadSession session = requireSession(tenantId, uploadId);
        ensureMutable(session);
        return session;
    }

    private GenericFileUploadSession requireSession(String tenantId, String uploadId) {
        GenericFileUploadSession session = uploadRepository.findSession(tenantId, uploadId)
            .orElseThrow(() -> notFound("file upload session not found: " + uploadId));
        return expireIfNeeded(session);
    }

    private GenericFileUploadSession expireIfNeeded(GenericFileUploadSession session) {
        if (!session.isExpired(now())) {
            return session;
        }
        return uploadRepository.saveSession(session.markExpired(now()));
    }

    private void ensureMutable(GenericFileUploadSession session) {
        if (session.uploadStatus() == AudioUploadStatus.EXPIRED || session.isExpired(now())) {
            throw gone(ErrorCode.UPLOAD_SESSION_EXPIRED, "upload session expired");
        }
        if (session.uploadStatus() == AudioUploadStatus.ABORTED) {
            throw conflict(ErrorCode.UPLOAD_ALREADY_ABORTED, "upload session already aborted");
        }
        if (session.uploadStatus() == AudioUploadStatus.COMPLETED) {
            throw conflict(ErrorCode.UPLOAD_ALREADY_COMPLETED, "upload session already completed");
        }
    }

    private GenericFileUploadSessionDTO toDto(GenericFileUploadSession session) {
        List<GenericFileUploadPart> parts = uploadRepository.findParts(session.tenantId(), session.uploadId()).stream()
            .sorted(Comparator.comparingInt(GenericFileUploadPart::partNumber))
            .toList();
        return GenericFileUploadAssembler.toDto(session, parts);
    }

    private GenericFileUploadSessionDTO toDto(
        GenericFileUploadSession session,
        GenericFileUploadPart part,
        ObjectStorageGateway.PresignedUrl presigned
    ) {
        return new GenericFileUploadSessionDTO(
            session.uploadId(),
            session.expiresAt(),
            session.partSizeBytes(),
            session.maxPartCount(),
            session.objectKey(),
            session.bucket(),
            session.contentType(),
            session.fileName(),
            session.fileSizeBytes(),
            session.fileSha256(),
            session.fileId(),
            List.of(new GenericFileUploadPartDTO(
                part.partNumber(),
                part.partSha256(),
                part.sizeBytes(),
                part.etag(),
                presigned.url(),
                presigned.expiresAt(),
                presigned.headers()
            ))
        );
    }

    private GenericFileCompleteDTO toCompleteDto(GenericFileUploadSession session) {
        return meetingFileRepository.findById(session.tenantId(), session.fileId())
            .map(file -> new GenericFileCompleteDTO(file.fileId(), file.sha256(), file.sizeBytes(), file.contentType()))
            .orElseGet(() -> new GenericFileCompleteDTO(
                session.fileId(),
                session.fileSha256(),
                session.fileSizeBytes(),
                session.contentType()
            ));
    }

    private static void validateMime(String contentType) {
        if (contentType == null || !MIME_WHITELIST.contains(contentType)) {
            throw new ApplicationException(
                ErrorCode.FILE_MIME_NOT_ALLOWED,
                415,
                "MIME type is not allowed: " + contentType,
                false
            );
        }
    }

    private static void validateFileSize(long fileSizeBytes) {
        if (fileSizeBytes <= 0) {
            throw validation("fileSizeBytes must be positive");
        }
        if (fileSizeBytes > MAX_FILE_BYTES) {
            throw validation("fileSizeBytes exceeds 500 MiB generic upload limit");
        }
    }

    private static int resolvePartSize(long fileSizeBytes, Integer requestedPartSize) {
        int requested = requestedPartSize != null ? requestedPartSize : DEFAULT_PART_SIZE_BYTES;
        return Math.max(Math.max(requested, (int) fileSizeBytes), MIN_PART_SIZE_BYTES);
    }

    private static void validatePartNumber(int partNumber, int maxPartCount) {
        if (partNumber < 1 || partNumber > maxPartCount) {
            throw validation("partNumber must be between 1 and " + maxPartCount);
        }
    }

    private static void validateExpectedPartSet(
        GenericFileUploadSession session,
        List<CompleteGenericFileUploadCommand.PartCommand> requestedParts
    ) {
        int expectedPartCount = expectedPartCount(session);
        if (requestedParts.size() != expectedPartCount) {
            throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "complete parts do not match expected part count");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (CompleteGenericFileUploadCommand.PartCommand part : requestedParts) {
            if (!seen.add(part.partNumber())) {
                throw validation("duplicate partNumber: " + part.partNumber());
            }
            if (part.partNumber() < 1 || part.partNumber() > expectedPartCount) {
                throw conflict(ErrorCode.UPLOAD_INCOMPLETE_PARTS, "partNumber outside expected complete range");
            }
        }
    }

    private static String objectKey(String tenantId, String uploadId, String fileName) {
        return "tenants/" + tenantId + "/generic-files/" + uploadId + "/1";
    }

    private static int expectedPartCount(GenericFileUploadSession session) {
        return (int) Math.ceil((double) session.fileSizeBytes() / (double) session.partSizeBytes());
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

    private static ApplicationException notFound(String message) {
        return new ApplicationException(ErrorCode.FILE_UPLOAD_NOT_FOUND, 404, message, false);
    }
}
