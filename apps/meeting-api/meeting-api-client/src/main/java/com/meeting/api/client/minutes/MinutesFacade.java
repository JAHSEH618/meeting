package com.meeting.api.client.minutes;

import java.util.Optional;

public interface MinutesFacade {
    Optional<MinutesDTO> get(String tenantId, String meetingId);

    MinutesDTO regenerate(RegenerateMinutesCommand command);
}
