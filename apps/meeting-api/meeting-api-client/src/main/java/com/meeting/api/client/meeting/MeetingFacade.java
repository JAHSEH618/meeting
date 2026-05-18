package com.meeting.api.client.meeting;

import java.util.List;
import java.util.Optional;

public interface MeetingFacade {
    MeetingDTO create(CreateMeetingCommand command);

    Optional<MeetingDTO> get(String tenantId, String meetingId);

    List<MeetingDTO> list(String tenantId);

    DeleteMeetingResult delete(DeleteMeetingCommand command);
}
