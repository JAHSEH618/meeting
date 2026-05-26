package com.meeting.api.app.meeting;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.AttachMeetingDocumentCommand;
import com.meeting.api.client.meeting.DetachMeetingDocumentCommand;
import com.meeting.api.client.meeting.MeetingDocumentDTO;
import com.meeting.api.client.meeting.MeetingDocumentFacade;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.document.DocumentRepository.DocumentRecord;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingDocumentAttachedEvent;
import com.meeting.api.domain.meeting.MeetingDocumentDetachedEvent;
import com.meeting.api.domain.meeting.MeetingDocumentRepository;
import com.meeting.api.domain.meeting.MeetingDocumentRepository.MeetingDocumentJoinRow;
import com.meeting.api.domain.meeting.MeetingDocumentRepository.MeetingDocumentRecord;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Workstation D1 — attach / detach / list meeting↔document links.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Both meeting and document must exist and live in the same tenant.</li>
 *   <li>Effective security level of the link is {@code max(meeting, document)}.
 *       SECRET tier is rejected here (R4: fail-closed on the attach boundary).</li>
 *   <li>Duplicate active link (meeting + document) is a no-op idempotent return.</li>
 *   <li>Outbox event published in the same transaction (R6 outbox rule).</li>
 * </ul>
 */
@Service
public class MeetingDocumentApplicationService implements MeetingDocumentFacade {
    private static final Logger log = LoggerFactory.getLogger(MeetingDocumentApplicationService.class);

    private final MeetingRepository meetingRepository;
    private final DocumentRepository documentRepository;
    private final MeetingDocumentRepository linkRepository;
    private final MessagePublisher messagePublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    @Autowired
    public MeetingDocumentApplicationService(
        MeetingRepository meetingRepository,
        DocumentRepository documentRepository,
        MeetingDocumentRepository linkRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(meetingRepository, documentRepository, linkRepository, messagePublisher,
            tenantScopedTransaction, Clock.systemUTC());
    }
    public MeetingDocumentApplicationService(
        MeetingRepository meetingRepository,
        DocumentRepository documentRepository,
        MeetingDocumentRepository linkRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.meetingRepository = meetingRepository;
        this.documentRepository = documentRepository;
        this.linkRepository = linkRepository;
        this.messagePublisher = messagePublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public MeetingDocumentDTO attach(AttachMeetingDocumentCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.actorUserId(), command.requestId(), () -> {
            Meeting meeting = meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found: " + command.meetingId(), false
                ));
            DocumentRecord document = documentRepository.findById(command.tenantId(), command.documentId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 404,
                    "document not found: " + command.documentId(), false
                ));
            if (document.deletedAt() != null) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 422,
                    "document is soft-deleted: " + command.documentId(), false
                );
            }
            SecurityLevel effective = maxSecurity(meeting.securityLevel(), document.securityLevel());
            // R4: SECRET fail-closed when attaching reference docs — minutes prompt would otherwise
            // pull SECRET content into the LLM call.
            if (command.role() == DocumentRole.REFERENCE
                && (effective == SecurityLevel.CONFIDENTIAL || effective == SecurityLevel.SECRET)) {
                throw new ApplicationException(
                    ErrorCode.SECURITY_LEVEL_BLOCKED, 422,
                    "REFERENCE document role rejected: effective security level " + effective
                        + " forbids LLM context injection", false
                );
            }

            // Idempotent attach — return existing link as DTO when one already exists.
            var existing = linkRepository.findActive(command.tenantId(), command.meetingId(), command.documentId());
            if (existing.isPresent()) {
                MeetingDocumentRecord rec = existing.get();
                return new MeetingDocumentDTO(
                    rec.id(), rec.meetingId(), rec.documentId(), document.title(),
                    rec.role(), effective, rec.attachedBy(), rec.attachedAt()
                );
            }

            OffsetDateTime now = OffsetDateTime.now(clock);
            String linkId = "mdoc_" + UUID.randomUUID().toString().replace("-", "");
            String savedId = linkRepository.save(new MeetingDocumentRecord(
                linkId, command.tenantId(), command.meetingId(), command.documentId(),
                command.role(), command.actorUserId(), now
            ));
            messagePublisher.publish(new MeetingDocumentAttachedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.meetingId(),
                savedId,
                command.documentId(),
                command.role(),
                effective,
                command.actorUserId(),
                1L,
                now
            ));
            log.info(
                "meeting_document_attached tenant={} meeting={} document={} role={} effectiveSecurity={} by={}",
                command.tenantId(), command.meetingId(), command.documentId(),
                command.role(), effective, command.actorUserId()
            );
            return new MeetingDocumentDTO(
                savedId, command.meetingId(), command.documentId(), document.title(),
                command.role(), effective, command.actorUserId(), now
            );
        });
    }

    @Override
    public void detach(DetachMeetingDocumentCommand command) {
        tenantScopedTransaction.executeWithoutResult(command.tenantId(), command.actorUserId(), command.requestId(), () -> {
            meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found: " + command.meetingId(), false
                ));
            OffsetDateTime now = OffsetDateTime.now(clock);
            boolean deleted = linkRepository.softDelete(
                command.tenantId(), command.meetingId(), command.documentId(), now
            );
            if (!deleted) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 404,
                    "no active link for document " + command.documentId()
                        + " on meeting " + command.meetingId(), false
                );
            }
            messagePublisher.publish(new MeetingDocumentDetachedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.meetingId(),
                command.documentId(),
                command.actorUserId(),
                1L,
                now
            ));
            log.info(
                "meeting_document_detached tenant={} meeting={} document={} by={}",
                command.tenantId(), command.meetingId(), command.documentId(), command.actorUserId()
            );
        });
    }

    @Override
    public List<MeetingDocumentDTO> list(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            meetingRepository.findById(tenantId, meetingId)
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found: " + meetingId, false
                ));
            List<MeetingDocumentJoinRow> rows = linkRepository.listByMeeting(tenantId, meetingId);
            return rows.stream()
                .map(r -> new MeetingDocumentDTO(
                    r.linkId(), r.meetingId(), r.documentId(), r.documentTitle(),
                    r.role(), r.documentSecurityLevel(), r.attachedBy(), r.attachedAt()
                ))
                .toList();
        });
    }

    private static SecurityLevel maxSecurity(SecurityLevel a, SecurityLevel b) {
        // Ordinal ordering matches enum declaration: PUBLIC < INTERNAL < CONFIDENTIAL < SECRET.
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
