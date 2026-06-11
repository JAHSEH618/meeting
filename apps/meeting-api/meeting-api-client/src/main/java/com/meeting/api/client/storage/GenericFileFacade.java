package com.meeting.api.client.storage;

import java.util.Optional;

public interface GenericFileFacade {
    GenericFileUploadSessionDTO createSession(CreateGenericFileUploadCommand command);

    GenericFileUploadPartDTO createPart(CreateGenericFilePartCommand command);

    GenericFileCompleteDTO complete(CompleteGenericFileUploadCommand command);

    void abort(AbortGenericFileUploadCommand command);

    Optional<GenericFileUploadSessionDTO> get(String tenantId, String uploadId);
}
