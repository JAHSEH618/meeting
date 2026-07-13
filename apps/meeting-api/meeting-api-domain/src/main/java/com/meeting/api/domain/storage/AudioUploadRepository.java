package com.meeting.api.domain.storage;

import java.util.List;
import java.util.Optional;

public interface AudioUploadRepository {
    AudioUploadSession saveSession(AudioUploadSession session);

    AudioUploadPart savePart(AudioUploadPart part);

    Optional<AudioUploadSession> findSession(String tenantId, String uploadId);

    /**
     * Loads the session with a pessimistic row lock ({@code FOR UPDATE}).
     * Must run inside a transaction; used by {@code complete()} so two
     * concurrent completes serialize instead of both passing the
     * status check and double-creating files/tasks.
     */
    Optional<AudioUploadSession> findSessionForUpdate(String tenantId, String uploadId);

    Optional<AudioUploadPart> findPart(String tenantId, String uploadId, int partNumber);

    List<AudioUploadPart> findParts(String tenantId, String uploadId);
}
