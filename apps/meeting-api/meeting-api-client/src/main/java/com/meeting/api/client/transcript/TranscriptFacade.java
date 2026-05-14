package com.meeting.api.client.transcript;

import java.util.Optional;

public interface TranscriptFacade {
    Optional<TranscriptDTO> get(String tenantId, String meetingId);
}
