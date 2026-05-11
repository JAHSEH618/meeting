package com.meeting.api.app.meeting;

import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final MeetingRepository meetingRepository;

    public MeetingApplicationService(MeetingRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    @Override
    @Transactional
    public MeetingDTO create(CreateMeetingCommand command) {
        // TODO: verify idempotency-key not already used
        // TODO: setTenantContext(command.tenantId())
        // TODO: write MeetingCreated event to domain_events_outbox

        Meeting meeting = Meeting.create(
            "m_" + UUID.randomUUID().toString().replace("-", ""),
            command.tenantId(),
            command.title(),
            command.securityLevel(),
            command.language(),
            command.participants(),
            command.createdBy()
        );
        Meeting saved = meetingRepository.save(meeting);
        return toDto(saved);
    }

    @Override
    public Optional<MeetingDTO> get(String tenantId, String meetingId) {
        return meetingRepository.findById(tenantId, meetingId).map(this::toDto);
    }

    @Override
    public List<MeetingDTO> list(String tenantId) {
        return meetingRepository.findByTenantId(tenantId).stream().map(this::toDto).toList();
    }

    private MeetingDTO toDto(Meeting meeting) {
        return new MeetingDTO(
            meeting.id(),
            meeting.tenantId(),
            meeting.title(),
            meeting.securityLevel(),
            meeting.status(),
            meeting.language(),
            meeting.transcriptVersion(),
            meeting.minutesVersion(),
            meeting.createdAt()
        );
    }
}
