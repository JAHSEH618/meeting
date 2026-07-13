package com.meeting.api.domain.storage;

import java.util.List;
import java.util.Optional;

public interface GenericFileUploadRepository {
    GenericFileUploadSession saveSession(GenericFileUploadSession session);

    GenericFileUploadPart savePart(GenericFileUploadPart part);

    Optional<GenericFileUploadSession> findSession(String tenantId, String uploadId);

    /**
     * Loads the session with a pessimistic row lock ({@code FOR UPDATE}).
     * Must run inside a transaction; used by {@code complete()} so two
     * concurrent completes serialize instead of both passing the
     * status check and double-creating files.
     */
    Optional<GenericFileUploadSession> findSessionForUpdate(String tenantId, String uploadId);

    Optional<GenericFileUploadPart> findPart(String tenantId, String uploadId, int partNumber);

    List<GenericFileUploadPart> findParts(String tenantId, String uploadId);
}
