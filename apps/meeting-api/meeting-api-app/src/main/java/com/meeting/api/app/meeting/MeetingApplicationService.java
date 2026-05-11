package com.meeting.api.app.meeting;

import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.MeetingFacade;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MeetingApplicationService implements MeetingFacade {
    private final MeetingRepository meetingRepository;

    public MeetingApplicationService(MeetingRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    @Override
    public MeetingDTO create(CreateMeetingCommand command) {
        Meeting meeting = Meeting.create(
            "m_" + UUID.randomUUID().toString().replace("-", ""),
            command.tenantId(),
            command.title(),
            command.securityLevel(),
            command.language()
        );
        return toDto(meetingRepository.save(meeting));
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
