package com.meeting.api.app.meeting;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.meeting.GlossaryTermDTO;
import com.meeting.api.client.meeting.MeetingGlossaryDTO;
import com.meeting.api.client.meeting.MeetingGlossaryFacade;
import com.meeting.api.client.meeting.UpdateMeetingGlossaryCommand;
import com.meeting.api.domain.meeting.MeetingGlossaryRepository;
import com.meeting.api.domain.meeting.MeetingGlossaryRepository.GlossaryTerm;
import com.meeting.api.domain.meeting.MeetingGlossaryUpdatedEvent;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Workstation D2 — overwrite-style meeting glossary management.
 *
 * <p>Validation:
 * <ul>
 *   <li>at most 200 terms (see {@code MAX_TERMS})</li>
 *   <li>each {@code term} length 1..64 chars; trimmed</li>
 *   <li>case-insensitive uniqueness on {@code term}</li>
 *   <li>aliases trimmed + deduped per-term, max 16 aliases</li>
 * </ul>
 */
@Service
public class MeetingGlossaryApplicationService implements MeetingGlossaryFacade {
    private static final Logger log = LoggerFactory.getLogger(MeetingGlossaryApplicationService.class);

    private static final int MAX_TERMS = 200;
    private static final int MAX_TERM_LENGTH = 64;
    private static final int MAX_DEFINITION_LENGTH = 256;
    private static final int MAX_ALIASES = 16;

    private final MeetingRepository meetingRepository;
    private final MeetingGlossaryRepository glossaryRepository;
    private final MessagePublisher messagePublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    @Autowired
    public MeetingGlossaryApplicationService(
        MeetingRepository meetingRepository,
        MeetingGlossaryRepository glossaryRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(meetingRepository, glossaryRepository, messagePublisher, tenantScopedTransaction, Clock.systemUTC());
    }
    public MeetingGlossaryApplicationService(
        MeetingRepository meetingRepository,
        MeetingGlossaryRepository glossaryRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.meetingRepository = meetingRepository;
        this.glossaryRepository = glossaryRepository;
        this.messagePublisher = messagePublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public Optional<MeetingGlossaryDTO> get(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> {
            meetingRepository.findById(tenantId, meetingId)
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found: " + meetingId, false
                ));
            Optional<List<GlossaryTerm>> stored = glossaryRepository.findByMeetingId(tenantId, meetingId);
            if (stored.isEmpty()) {
                return Optional.of(new MeetingGlossaryDTO(meetingId, List.of(), null));
            }
            List<GlossaryTermDTO> dtos = stored.get().stream()
                .map(t -> new GlossaryTermDTO(t.term(), t.definition(), t.aliases() == null ? List.of() : t.aliases()))
                .toList();
            return Optional.of(new MeetingGlossaryDTO(meetingId, dtos, null));
        });
    }

    @Override
    public MeetingGlossaryDTO update(UpdateMeetingGlossaryCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.actorUserId(), command.requestId(), () -> {
            meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.MEETING_NOT_FOUND, 404,
                    "meeting not found: " + command.meetingId(), false
                ));
            List<GlossaryTerm> normalized = normalize(command.terms());
            OffsetDateTime now = OffsetDateTime.now(clock);
            OffsetDateTime updatedAt = glossaryRepository.replace(
                command.tenantId(), command.meetingId(), normalized, now
            );
            messagePublisher.publish(new MeetingGlossaryUpdatedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.meetingId(),
                normalized.size(),
                command.actorUserId(),
                1L,
                now
            ));
            log.info(
                "meeting_glossary_updated tenant={} meeting={} terms={} by={}",
                command.tenantId(), command.meetingId(), normalized.size(), command.actorUserId()
            );
            return new MeetingGlossaryDTO(
                command.meetingId(),
                normalized.stream()
                    .map(t -> new GlossaryTermDTO(t.term(), t.definition(), t.aliases()))
                    .toList(),
                updatedAt
            );
        });
    }

    private static List<GlossaryTerm> normalize(List<GlossaryTermDTO> input) {
        if (input == null) {
            return List.of();
        }
        if (input.size() > MAX_TERMS) {
            throw new ApplicationException(
                ErrorCode.VALIDATION_FAILED, 422,
                "glossary has too many terms: " + input.size() + " > " + MAX_TERMS, false
            );
        }
        Set<String> seen = new HashSet<>();
        List<GlossaryTerm> normalized = new ArrayList<>(input.size());
        for (GlossaryTermDTO raw : input) {
            if (raw == null || raw.term() == null) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 422, "glossary entry is null", false
                );
            }
            String term = raw.term().strip();
            if (term.isEmpty() || term.length() > MAX_TERM_LENGTH) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 422,
                    "glossary term length out of range: " + raw.term(), false
                );
            }
            String fold = term.toLowerCase();
            if (!seen.add(fold)) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 422,
                    "duplicate glossary term: " + term, false
                );
            }
            String definition = raw.definition() == null ? null : raw.definition().strip();
            if (definition != null && definition.length() > MAX_DEFINITION_LENGTH) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 422,
                    "glossary definition too long for term: " + term, false
                );
            }
            List<String> aliases = List.of();
            if (raw.aliases() != null && !raw.aliases().isEmpty()) {
                if (raw.aliases().size() > MAX_ALIASES) {
                    throw new ApplicationException(
                        ErrorCode.VALIDATION_FAILED, 422,
                        "too many aliases for term: " + term, false
                    );
                }
                Set<String> aliasSeen = new HashSet<>();
                List<String> cleaned = new ArrayList<>();
                for (String a : raw.aliases()) {
                    if (a == null) continue;
                    String trimmed = a.strip();
                    if (trimmed.isEmpty() || trimmed.length() > MAX_TERM_LENGTH) {
                        throw new ApplicationException(
                            ErrorCode.VALIDATION_FAILED, 422,
                            "alias length out of range on term: " + term, false
                        );
                    }
                    if (aliasSeen.add(trimmed.toLowerCase())) {
                        cleaned.add(trimmed);
                    }
                }
                aliases = List.copyOf(cleaned);
            }
            normalized.add(new GlossaryTerm(term, definition, aliases));
        }
        return List.copyOf(normalized);
    }
}
