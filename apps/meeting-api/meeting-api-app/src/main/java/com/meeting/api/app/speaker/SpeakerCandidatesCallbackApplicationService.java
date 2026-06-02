package com.meeting.api.app.speaker;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.SpeakerCandidatesCallbackCommand;
import com.meeting.api.domain.kms.EmbeddingEnvelopeGateway;
import com.meeting.api.domain.kms.EncryptedEmbedding;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository.SpeakerCandidate;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Speaker-candidates callback handler.
 *
 * <p>Receives plaintext speaker embeddings over the internal TLS + HMAC callback,
 * envelope-encrypts them via {@link EmbeddingEnvelopeGateway}, persists ciphertext-only
 * into {@code speaker_embeddings}, and stores the candidate profile list into
 * {@code meeting_speakers} for user confirmation.</p>
 *
 * <p>Plaintext embeddings live only for the duration of this method; arrays are
 * zeroized in the finally block. Plaintext is never logged or persisted.</p>
 */
@Service
public class SpeakerCandidatesCallbackApplicationService {
    private static final Logger log = LoggerFactory.getLogger(SpeakerCandidatesCallbackApplicationService.class);

    private final ProcessingTaskRepository taskRepository;
    private final CallbackEventRepository callbackEventRepository;
    private final SpeakerProfileRepository speakerProfileRepository;
    private final SpeakerEmbeddingRepository speakerEmbeddingRepository;
    private final MeetingSpeakerRepository meetingSpeakerRepository;
    private final EmbeddingEnvelopeGateway envelopeGateway;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final CallbackSecurityVerifier securityVerifier;
    private final Clock clock;

    @Autowired
    public SpeakerCandidatesCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        SpeakerProfileRepository speakerProfileRepository,
        SpeakerEmbeddingRepository speakerEmbeddingRepository,
        MeetingSpeakerRepository meetingSpeakerRepository,
        EmbeddingEnvelopeGateway envelopeGateway,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier
    ) {
        this(taskRepository, callbackEventRepository, speakerProfileRepository,
            speakerEmbeddingRepository, meetingSpeakerRepository, envelopeGateway,
            tenantScopedTransaction, securityVerifier, Clock.systemUTC());
    }
    public SpeakerCandidatesCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        SpeakerProfileRepository speakerProfileRepository,
        SpeakerEmbeddingRepository speakerEmbeddingRepository,
        MeetingSpeakerRepository meetingSpeakerRepository,
        EmbeddingEnvelopeGateway envelopeGateway,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.callbackEventRepository = callbackEventRepository;
        this.speakerProfileRepository = speakerProfileRepository;
        this.speakerEmbeddingRepository = speakerEmbeddingRepository;
        this.meetingSpeakerRepository = meetingSpeakerRepository;
        this.envelopeGateway = envelopeGateway;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.securityVerifier = securityVerifier;
        this.clock = clock;
    }
    public void writeCandidates(SpeakerCandidatesCallbackCommand command) {
        securityVerifier.verify(command.metadata());
        tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));
            if (task.attemptNo() != command.attemptNo()) {
                throw new IllegalStateException("callback attempt does not match current attempt");
            }
            if (!persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata())) {
                return null;
            }

            Set<String> authorizedProfileIds = collectAuthorizedProfileIds(command.tenantId(), command.speakers());
            OffsetDateTime now = OffsetDateTime.now(clock);

            for (var speaker : command.speakers()) {
                List<String> filteredCandidatePersonIds = new ArrayList<>();
                List<SpeakerCandidate> filteredCandidates = new ArrayList<>();
                double topConfidence = 0.0;
                for (var candidate : speaker.candidates()) {
                    if (candidate.speakerProfileId() == null) continue;
                    if (!authorizedProfileIds.contains(candidate.speakerProfileId())) {
                        log.warn("speaker_candidate_skipped_unauthorized tenant={} taskId={} profileId={}",
                            command.tenantId(), command.taskId(), candidate.speakerProfileId());
                        continue;
                    }
                    if (candidate.personId() != null) {
                        filteredCandidatePersonIds.add(candidate.personId());
                    }
                    filteredCandidates.add(new SpeakerCandidate(
                        candidate.personId(),
                        candidate.speakerProfileId(),
                        candidate.confidence()
                    ));
                    if (candidate.confidence() > topConfidence) {
                        topConfidence = candidate.confidence();
                    }
                }

                EncryptedEmbedding encrypted = null;
                float[] plaintext = speaker.embedding() == null ? null : speaker.embedding().values();
                try {
                    if (plaintext != null && plaintext.length > 0) {
                        encrypted = envelopeGateway.encrypt(command.tenantId(), plaintext);
                    }
                } finally {
                    zeroFloats(plaintext);
                }

                if (encrypted != null && command.meetingId() != null) {
                    speakerEmbeddingRepository.save(new SpeakerEmbeddingRepository.SpeakerEmbeddingRecord(
                        "emb_" + UUID.randomUUID().toString().replace("-", ""),
                        command.tenantId(),
                        null,
                        null,
                        "ACTIVE",
                        encrypted.keyId(),
                        encrypted.wrappedDek(),
                        encrypted.algorithm(),
                        encrypted.ciphertext(),
                        encrypted.plaintextHash(),
                        null,
                        speaker.embedding() == null ? null : Double.NaN,
                        speaker.embedding() == null ? null : speaker.embedding().modelVersion(),
                        null,
                        null,
                        now
                    ));
                }
                if (command.meetingId() != null) {
                    meetingSpeakerRepository.saveCandidates(
                        command.tenantId(),
                        command.meetingId(),
                        speaker.speakerLabel(),
                        filteredCandidatePersonIds,
                        filteredCandidates,
                        topConfidence == 0.0 ? null : topConfidence,
                        "AI_MATCH",
                        now
                    );
                }
            }
            log.info("speaker_candidates_persisted tenant={} task={} speakers={}",
                command.tenantId(), command.taskId(), command.speakers().size());
            return null;
        });
    }

    private Set<String> collectAuthorizedProfileIds(String tenantId, List<SpeakerCandidatesCallbackCommand.SpeakerEntry> speakers) {
        Set<String> requested = new HashSet<>();
        for (var s : speakers) {
            for (var c : s.candidates()) {
                if (c.speakerProfileId() != null) {
                    requested.add(c.speakerProfileId());
                }
            }
        }
        if (requested.isEmpty()) return Set.of();
        List<SpeakerProfile> found = speakerProfileRepository.findByIds(tenantId, new ArrayList<>(requested));
        Set<String> active = new HashSet<>();
        for (var p : found) {
            if (p.isActive()) {
                active.add(p.id());
            }
        }
        return active;
    }

    private boolean persistCallbackEvent(String tenantId, String taskId, CallbackMetadata metadata) {
        var existing = callbackEventRepository.findByIdempotencyKey(tenantId, metadata.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().bodySha256().equals(metadata.bodySha256())) {
                throw new IllegalStateException("callback idempotency body hash conflict");
            }
            return false;
        }
        callbackEventRepository.save(new CallbackEventRepository.CallbackEventRecord(
            tenantId, taskId, metadata.workerId(), metadata.idempotencyKey(),
            metadata.bodySha256(), metadata.attemptNo(), metadata.leaseOwner(),
            "", 200, null, metadata.traceId(), OffsetDateTime.now(clock)
        ));
        return true;
    }

    private static void zeroFloats(float[] values) {
        if (values == null) return;
        for (int i = 0; i < values.length; i++) {
            values[i] = 0f;
        }
    }
}
