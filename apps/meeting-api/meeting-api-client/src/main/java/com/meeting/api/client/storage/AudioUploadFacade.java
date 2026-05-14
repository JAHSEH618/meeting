package com.meeting.api.client.storage;

import java.util.Optional;

public interface AudioUploadFacade {
    AudioUploadSessionDTO createSession(CreateAudioUploadSessionCommand command);

    AudioUploadPartUploadDTO createPart(CreateAudioUploadPartCommand command);

    AudioUploadSessionDTO complete(CompleteAudioUploadCommand command);

    AudioUploadSessionDTO abort(AbortAudioUploadCommand command);

    Optional<AudioUploadSessionDTO> get(String tenantId, String meetingId, String uploadId);
}
