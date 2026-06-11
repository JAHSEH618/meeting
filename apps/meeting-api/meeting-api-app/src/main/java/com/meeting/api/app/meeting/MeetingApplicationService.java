package com.meeting.api.app.meeting;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingResult;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import com.meeting.api.client.meeting.UpdateMeetingCommand;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingCreatedEvent;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Meeting application service — use-case orchestration.
 *
 * TODO before production (spec §3, §7):
 * - Tenant context must be set on the DB connection (SET app.tenant_id = ?) at transaction start.
 * - Domain events must be written to domain_events_outbox in the same transaction (§7 outbox rule).
 * - Idempotency-key dedup check.
 * - Permission / authorization check.
 * - E6 fix: participants are now passed through to the domain model.
 */
@Service
public class MeetingApplicationService implements MeetingFacade {
    private static final Logger log = LoggerFactory.getLogger(MeetingApplicationService.class);

    static final String RESOURCE_TYPE_MEETING = "MEETING";
    static final String LEGAL_HOLD_SCOPE_MEETING = "MEETING";

    private final MeetingRepository meetingRepository;
    private final MessagePublisher messagePublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final LegalHoldCheckPort legalHoldCheck;
    private final AuditEventLogger auditLogger;
    private final Clock clock;

    @Autowired
    public MeetingApplicationService(
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        LegalHoldCheckPort legalHoldCheck,
        AuditEventLogger auditLogger
    ) {
        this(
            meetingRepository,
            messagePublisher,
            tenantScopedTransaction,
            legalHoldCheck,
            auditLogger,
            Clock.systemUTC()
        );
    }
    public MeetingApplicationService(
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        LegalHoldCheckPort legalHoldCheck,
        AuditEventLogger auditLogger,
        Clock clock
    ) {
        this.meetingRepository = meetingRepository;
        this.messagePublisher = messagePublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.legalHoldCheck = legalHoldCheck;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @Override
    public MeetingDTO create(CreateMeetingCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.createdBy(), null, () -> {
            Meeting meeting = Meeting.create(
                "m_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.title(),
                command.scheduledStartAt(),
                command.securityLevel(),
                command.language(),
                command.participants(),
                command.createdBy()
            );
            Meeting saved = meetingRepository.save(meeting);
            publishMeetingCreated(saved);
            return toDto(saved);
        });
    }

    @Override
    public Optional<MeetingDTO> get(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> meetingRepository.findById(tenantId, meetingId).map(this::toDto));
    }

    @Override
    public List<MeetingDTO> list(String tenantId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> meetingRepository.findByTenantId(tenantId).stream().map(this::toDto).toList());
    }

    @Override
    public MeetingDTO update(UpdateMeetingCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.actorUserId(), command.requestId(), () -> {
            Meeting meeting = meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found: " + command.meetingId(), false
                ));

            if (command.expectedVersion() != null
                && command.expectedVersion() != meeting.transcriptVersion()) {
                throw new ApplicationException(
                    ErrorCode.VERSION_CONFLICT, 409,
                    "meeting was modified: expected version="
                        + command.expectedVersion()
                        + " actual=" + meeting.transcriptVersion(),
                    false
                );
            }

            Meeting updated = meeting.update(
                normalizeTitle(command.title()),
                command.scheduledStartAt(),
                command.scheduledStartAtProvided(),
                command.participants() == null ? null : toParticipants(command.participants())
            );
            return toDto(meetingRepository.save(updated));
        });
    }

    @Override
    public DeleteMeetingResult delete(DeleteMeetingCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.actorUserId(), command.requestId(), () -> {
            Meeting meeting = meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found: " + command.meetingId(), false
                ));

            if (command.expectedTranscriptVersion() != null
                && command.expectedTranscriptVersion() != meeting.transcriptVersion()) {
                logBlocked(command, "version mismatch");
                throw new ApplicationException(
                    ErrorCode.VERSION_CONFLICT, 409,
                    "meeting was modified: expected transcriptVersion="
                        + command.expectedTranscriptVersion()
                        + " actual=" + meeting.transcriptVersion(),
                    false
                );
            }

            if (legalHoldCheck.isProtected(
                command.tenantId(), LEGAL_HOLD_SCOPE_MEETING, command.meetingId()
            )) {
                logBlocked(command, "legal hold");
                throw new ApplicationException(
                    ErrorCode.LEGAL_HOLD_BLOCKED, 423,
                    "meeting is under legal hold: " + command.meetingId(),
                    false
                );
            }

            boolean affected = meetingRepository.markDeleted(
                command.tenantId(), command.meetingId()
            );
            if (!affected) {
                throw new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found or already deleted: " + command.meetingId(),
                    false
                );
            }

            OffsetDateTime now = OffsetDateTime.now(clock);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", MeetingStatus.DELETED.name());
            if (command.reason() != null && !command.reason().isBlank()) {
                payload.put("reason", command.reason());
            }
            if (command.expectedTranscriptVersion() != null) {
                payload.put("expectedTranscriptVersion", command.expectedTranscriptVersion());
            }
            payload.put("legalHoldAcknowledged", command.legalHoldAcknowledged());

            auditLogger.log(AuditEntry.success(
                command.tenantId(), command.actorUserId(),
                AuditAction.DELETE,
                RESOURCE_TYPE_MEETING, command.meetingId(),
                payload,
                command.requestId()
            ));
            log.info(
                "meeting_deleted tenant={} meeting={} actor={} reason={}",
                command.tenantId(), command.meetingId(), command.actorUserId(),
                command.reason() == null ? "" : command.reason()
            );

            return new DeleteMeetingResult(command.meetingId(), MeetingStatus.DELETED, now);
        });
    }

    private void logBlocked(DeleteMeetingCommand command, String reason) {
        auditLogger.log(AuditEntry.blocked(
            command.tenantId(), command.actorUserId(),
            AuditAction.DELETE,
            RESOURCE_TYPE_MEETING, command.meetingId(),
            reason, command.requestId()
        ));
        log.info(
            "meeting_delete_blocked tenant={} meeting={} actor={} reason={}",
            command.tenantId(), command.meetingId(), command.actorUserId(), reason
        );
    }

    private MeetingDTO toDto(Meeting meeting) {
        return new MeetingDTO(
            meeting.id(),
            meeting.tenantId(),
            meeting.title(),
            meeting.scheduledStartAt(),
            meeting.securityLevel(),
            meeting.status(),
            meeting.language(),
            meeting.transcriptVersion(),
            meeting.minutesVersion(),
            meeting.createdAt(),
            meeting.participants().stream()
                .map(participant -> new MeetingDTO.ParticipantDTO(
                    participant.personId(),
                    participant.displayName(),
                    participant.role()
                ))
                .toList()
        );
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String trimmed = title.trim();
        if (trimmed.isEmpty()) {
            throw new ApplicationException(
                ErrorCode.VALIDATION_FAILED, 422,
                "meeting title must not be blank", false
            );
        }
        return trimmed;
    }

    private static List<Meeting.Participant> toParticipants(
        List<CreateMeetingCommand.ParticipantCommand> commands
    ) {
        List<Meeting.Participant> participants = new ArrayList<>();
        Set<String> personIds = new LinkedHashSet<>();
        for (CreateMeetingCommand.ParticipantCommand command : commands) {
            if (command == null) {
                throw validation("participant must not be null");
            }
            String personId = requireText(command.personId(), "participant personId");
            String displayName = requireText(command.displayName(), "participant displayName");
            String role = requireText(command.role(), "participant role");
            if (!personIds.add(personId)) {
                throw validation("duplicate participant personId: " + personId);
            }
            participants.add(new Meeting.Participant(personId, displayName, role));
        }
        return participants;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw validation(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static ApplicationException validation(String message) {
        return new ApplicationException(ErrorCode.VALIDATION_FAILED, 422, message, false);
    }

    private void publishMeetingCreated(Meeting meeting) {
        if (messagePublisher == null) {
            return;
        }
        messagePublisher.publish(new MeetingCreatedEvent(
            "evt_" + UUID.randomUUID().toString().replace("-", ""),
            meeting.tenantId(),
            meeting.id(),
            meeting.title(),
            meeting.securityLevel(),
            meeting.createdBy(),
            0,
            meeting.createdAt()
        ));
        log.debug("MeetingCreatedEvent queued for meeting {}", meeting.id());
    }
}
