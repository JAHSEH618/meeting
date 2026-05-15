package com.meeting.api.client.document;

import java.util.List;
import java.util.Optional;

public interface DocumentFacade {
    DocumentDTO create(CreateDocumentCommand command);

    Optional<DocumentDTO> get(String tenantId, String documentId);

    List<DocumentDTO> list(String tenantId);

    void delete(String tenantId, String documentId, String deletedBy);

    /**
     * Reindex an existing document — re-parse text, re-chunk, mark previous chunks STALE,
     * and trigger embedding regeneration. Returns updated DocumentDTO.
     */
    DocumentDTO reindex(String tenantId, String documentId, String requestedBy);
}
