package com.meeting.api.client.meeting;

import java.util.Optional;

public interface MeetingGlossaryFacade {
    Optional<MeetingGlossaryDTO> get(String tenantId, String meetingId);

    MeetingGlossaryDTO update(UpdateMeetingGlossaryCommand command);
}
