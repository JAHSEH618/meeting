package com.meeting.api.app.speaker;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SpeakerEnrollmentCallbackApplicationService {
    private static final Logger log = LoggerFactory.getLogger(SpeakerEnrollmentCallbackApplicationService.class);

    private final ProcessingTaskRepository taskRepository;
    private final CallbackEventRepository callbackEventRepository;
    private final SpeakerProfileRepository speakerProfileRepository;
    private final SpeakerEnrollmentRepository speakerEnrollmentRepository;
    private final SpeakerEmbeddingRepository speakerEmbeddingRepository;
    private final EmbeddingEnvelopeGateway envelopeGateway;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final CallbackSecurityVerifier securityVerifier;
    private final Clock clock;

    @Autowired
    public SpeakerEnrollmentCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        SpeakerProfileRepository speakerProfileRepository,
        SpeakerEnrollmentRepository speakerEnrollmentRepository,
        SpeakerEmbeddingRepository speakerEmbeddingRepository,
        EmbeddingEnvelopeGateway envelopeGateway,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier
    ) {
        this(taskRepository, callbackEventRepository, speakerProfileRepository, speakerEnrollmentRepository,
            speakerEmbeddingRepository, envelopeGateway, tenantScopedTransaction, securityVerifier, Clock.systemUTC());
    }

    public SpeakerEnrollmentCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        SpeakerProfileRepository speakerProfileRepository,
        SpeakerEnrollmentRepository speakerEnrollmentRepository,
        SpeakerEmbeddingRepository speakerEmbeddingRepository,
        EmbeddingEnvelopeGateway envelopeGateway,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.callbackEventRepository = callbackEventRepository;
        this.speakerProfileRepository = speakerProfileRepository;
        this.speakerEnrollmentRepository = speakerEnrollmentRepository;
        this.speakerEmbeddingRepository = speakerEmbeddingRepository;
        this.envelopeGateway = envelopeGateway;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.securityVerifier = securityVerifier;
        this.clock = clock;
    }

    public EnrollmentResult writeEnrollment(SpeakerEnrollmentCallbackCommand command) {
        securityVerifier.verify(
            command.metadata(),
            command.tenantId(),
            command.metadata().workerId(),
            command.taskId(),
            "SPEAKER_ENROLLMENT"
        );
        return tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));
            if (!ProcessingTaskApplicationService.SPEAKER_ENROLLMENT.equals(task.taskType())) {
                throw new IllegalStateException("callback task is not SPEAKER_ENROLLMENT");
            }
            if (task.meetingId() != null) {
                throw new IllegalStateException("speaker enrollment task must not be meeting scoped");
            }
            if (task.attemptNo() != command.attemptNo()) {
                throw new IllegalStateException("callback attempt does not match current attempt");
            }
            if (!command.metadata().leaseOwner().equals(task.leaseOwner())) {
                throw new IllegalStateException("callback lease owner does not match current lease");
            }
            if (!persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata())) {
                log.info("speaker_enrollment_callback_replay tenant={} task={} enrollment={}",
                    command.tenantId(), command.taskId(), command.speakerEnrollmentId());
                return new EnrollmentResult(command.speakerEnrollmentId(), true);
            }

            SpeakerProfile profile = speakerProfileRepository.findById(command.tenantId(), command.speakerProfileId())
                .orElseThrow(() -> new IllegalArgumentException("speaker profile not found: " + command.speakerProfileId()));
            if (!profile.isActive()) {
                throw new IllegalStateException("speaker profile is not ACTIVE: " + command.speakerProfileId());
            }
            var enrollment = speakerEnrollmentRepository.findById(command.tenantId(), command.speakerEnrollmentId())
                .orElseThrow(() -> new IllegalArgumentException("speaker enrollment not found: " + command.speakerEnrollmentId()));
            if (!command.speakerProfileId().equals(enrollment.speakerProfileId())) {
                throw new IllegalStateException("speaker enrollment does not belong to profile");
            }
            if (!command.audioFileId().equals(enrollment.sourceAudioFileId())) {
                throw new IllegalStateException("speaker enrollment audio file does not match callback");
            }
            if (command.embedding() == null || command.embedding().values() == null || command.embedding().values().length == 0) {
                throw new IllegalArgumentException("speaker enrollment embedding values are required");
            }
            if (command.embedding().modelVersion() == null || command.embedding().modelVersion().isBlank()) {
                throw new IllegalArgumentException("speaker enrollment embedding modelVersion is required");
            }

            OffsetDateTime now = OffsetDateTime.now(clock);
            float[] plaintext = command.embedding().values();
            EncryptedEmbedding encrypted;
            try {
                encrypted = envelopeGateway.encrypt(command.tenantId(), plaintext);
            } finally {
                Arrays.fill(plaintext, 0f);
            }

            speakerEmbeddingRepository.save(new SpeakerEmbeddingRepository.SpeakerEmbeddingRecord(
                "emb_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.speakerProfileId(),
                profile.personId(),
                "ACTIVE",
                encrypted.keyId(),
                encrypted.wrappedDek(),
                encrypted.algorithm(),
                ciphertextWithNonce(encrypted),
                encrypted.plaintextHash(),
                enrollment.sourceAudioFileId(),
                command.embedding().qualityScore(),
                command.embedding().modelVersion(),
                null,
                null,
                now
            ));
            speakerEnrollmentRepository.updateStatus(
                command.tenantId(),
                command.speakerEnrollmentId(),
                "SUCCEEDED",
                command.embedding().qualityScore(),
                command.embedding().modelVersion(),
                null,
                now
            );
            log.info("speaker_enrollment_persisted tenant={} task={} profile={} enrollment={}",
                command.tenantId(), command.taskId(), command.speakerProfileId(), command.speakerEnrollmentId());
            return new EnrollmentResult(command.speakerEnrollmentId(), false);
        });
    }

    private boolean persistCallbackEvent(String tenantId, String taskId, CallbackMetadata metadata) {
        var result = callbackEventRepository.recordOnce(new CallbackEventRepository.CallbackEventRecord(
            tenantId, taskId, metadata.workerId(), metadata.idempotencyKey(),
            metadata.bodySha256(), metadata.attemptNo(), metadata.leaseOwner(),
            "", 200, null, metadata.traceId(), OffsetDateTime.now(clock)
        ));
        if (result.status() == CallbackEventRepository.RecordStatus.BODY_HASH_CONFLICT) {
            throw new IllegalStateException("callback idempotency body hash conflict");
        }
        return result.status() == CallbackEventRepository.RecordStatus.RECORDED;
    }

    private static byte[] ciphertextWithNonce(EncryptedEmbedding encrypted) {
        byte[] nonce = encrypted.nonce() == null ? new byte[0] : encrypted.nonce();
        byte[] ciphertext = encrypted.ciphertext() == null ? new byte[0] : encrypted.ciphertext();
        byte[] out = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
        return out;
    }

    public record EnrollmentResult(String enrollmentId, boolean replayed) {
    }
}
