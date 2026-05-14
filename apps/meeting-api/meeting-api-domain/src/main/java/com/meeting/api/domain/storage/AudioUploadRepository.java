package com.meeting.api.domain.storage;

import java.util.List;
import java.util.Optional;

public interface AudioUploadRepository {
    AudioUploadSession saveSession(AudioUploadSession session);

    AudioUploadPart savePart(AudioUploadPart part);

    Optional<AudioUploadSession> findSession(String tenantId, String uploadId);

    Optional<AudioUploadPart> findPart(String tenantId, String uploadId, int partNumber);

    List<AudioUploadPart> findParts(String tenantId, String uploadId);
}
