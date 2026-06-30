package com.meeting.api.app.speaker;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.SpeakerCandidatesCallbackCommand;
import com.meeting.api.domain.kms.EmbeddingEnvelopeGateway;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository.SpeakerCandidate;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Speaker-candidates callback handler.
 *
 * <p>Receives plaintext speaker embeddings over the internal TLS + HMAC callback,
 * uses {@link EmbeddingEnvelopeGateway} only to avoid logging/persisting plaintext
 * vectors, and stores the candidate profile list into {@code meeting_speakers}
 * for user confirmation.</p>
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
        MeetingSpeakerRepository meetingSpeakerRepository,
        EmbeddingEnvelopeGateway envelopeGateway,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier
    ) {
        this(taskRepository, callbackEventRepository, speakerProfileRepository,
            meetingSpeakerRepository, envelopeGateway,
            tenantScopedTransaction, securityVerifier, Clock.systemUTC());
    }
    public SpeakerCandidatesCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        SpeakerProfileRepository speakerProfileRepository,
        MeetingSpeakerRepository meetingSpeakerRepository,
        EmbeddingEnvelopeGateway envelopeGateway,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.callbackEventRepository = callbackEventRepository;
        this.speakerProfileRepository = speakerProfileRepository;
        this.meetingSpeakerRepository = meetingSpeakerRepository;
        this.envelopeGateway = envelopeGateway;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.securityVerifier = securityVerifier;
        this.clock = clock;
    }
    public void writeCandidates(SpeakerCandidatesCallbackCommand command) {
        securityVerifier.verify(
            command.metadata(),
            command.tenantId(),
            command.metadata().workerId(),
            command.taskId(),
            "SPEAKER_CANDIDATES"
        );
        tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));
            if (!ProcessingTaskApplicationService.MEETING_FULL_PIPELINE.equals(task.taskType())) {
                throw new IllegalStateException("speaker candidates callback requires MEETING_FULL_PIPELINE task");
            }
            if (command.meetingId() == null || command.meetingId().isBlank()) {
                throw new IllegalArgumentException("meetingId is required for speaker candidates callback");
            }
            if (!command.meetingId().equals(task.meetingId())) {
                throw new IllegalStateException("callback meeting does not match task");
            }
            if (task.attemptNo() != command.attemptNo()) {
                throw new IllegalStateException("callback attempt does not match current attempt");
            }
            if (!command.metadata().leaseOwner().equals(task.leaseOwner())) {
                // Transient lease-owner mismatch: a bounded retry can succeed once the lease
                // settles, so surface a typed retryable 409 rather than a terminal conflict.
                throw new ApplicationException(
                    ErrorCode.TASK_LEASE_CONFLICT, 409,
                    "callback lease owner does not match current lease", true);
            }
            if (!persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata())) {
                return null;
            }

            Map<String, String> authorizedProfileOwners = collectAuthorizedProfileOwners(command.tenantId(), command.speakers());
            OffsetDateTime now = OffsetDateTime.now(clock);

            for (var speaker : command.speakers()) {
                List<String> filteredCandidatePersonIds = new ArrayList<>();
                List<SpeakerCandidate> filteredCandidates = new ArrayList<>();
                double topConfidence = 0.0;
                for (var candidate : speaker.candidates()) {
                    if (candidate.speakerProfileId() == null) continue;
                    String profilePersonId = authorizedProfileOwners.get(candidate.speakerProfileId());
                    if (profilePersonId == null) {
                        log.warn("speaker_candidate_skipped_unauthorized tenant={} taskId={} profileId={}",
                            command.tenantId(), command.taskId(), candidate.speakerProfileId());
                        continue;
                    }
                    if (candidate.personId() != null && !candidate.personId().equals(profilePersonId)) {
                        log.warn("speaker_candidate_person_rewritten tenant={} taskId={} profileId={}",
                            command.tenantId(), command.taskId(), candidate.speakerProfileId());
                    }
                    filteredCandidatePersonIds.add(profilePersonId);
                    filteredCandidates.add(new SpeakerCandidate(
                        profilePersonId,
                        candidate.speakerProfileId(),
                        candidate.confidence()
                    ));
                    if (candidate.confidence() > topConfidence) {
                        topConfidence = candidate.confidence();
                    }
                }

                float[] plaintext = speaker.embedding() == null ? null : speaker.embedding().values();
                try {
                    if (plaintext != null && plaintext.length > 0) {
                        envelopeGateway.encrypt(command.tenantId(), plaintext);
                    }
                } finally {
                    zeroFloats(plaintext);
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

    private Map<String, String> collectAuthorizedProfileOwners(String tenantId, List<SpeakerCandidatesCallbackCommand.SpeakerEntry> speakers) {
        Set<String> requested = new HashSet<>();
        for (var s : speakers) {
            for (var c : s.candidates()) {
                if (c.speakerProfileId() != null) {
                    requested.add(c.speakerProfileId());
                }
            }
        }
        if (requested.isEmpty()) return Map.of();
        List<SpeakerProfile> found = speakerProfileRepository.findByIds(tenantId, new ArrayList<>(requested));
        Map<String, String> active = new HashMap<>();
        for (var p : found) {
            if (p.isActive()) {
                active.put(p.id(), p.personId());
            }
        }
        return active;
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

    private static void zeroFloats(float[] values) {
        if (values == null) return;
        for (int i = 0; i < values.length; i++) {
            values[i] = 0f;
        }
    }
}
