package com.meeting.api.domain.storage;

import java.util.List;
import java.util.Optional;

public interface GenericFileUploadRepository {
    GenericFileUploadSession saveSession(GenericFileUploadSession session);

    GenericFileUploadPart savePart(GenericFileUploadPart part);

    Optional<GenericFileUploadSession> findSession(String tenantId, String uploadId);

    Optional<GenericFileUploadPart> findPart(String tenantId, String uploadId, int partNumber);

    List<GenericFileUploadPart> findParts(String tenantId, String uploadId);
}
