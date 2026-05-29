package com.meeting.api.app.storage;

import com.meeting.api.client.storage.GenericFileUploadPartDTO;
import com.meeting.api.client.storage.GenericFileUploadSessionDTO;
import com.meeting.api.domain.storage.GenericFileUploadPart;
import com.meeting.api.domain.storage.GenericFileUploadSession;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class GenericFileUploadAssembler {
    private GenericFileUploadAssembler() {
    }

    static GenericFileUploadSessionDTO toDto(GenericFileUploadSession session, List<GenericFileUploadPart> parts) {
        return new GenericFileUploadSessionDTO(
            session.uploadId(),
            session.expiresAt(),
            session.partSizeBytes(),
            session.maxPartCount(),
            session.objectKey(),
            session.bucket(),
            session.contentType(),
            session.fileName(),
            session.fileSizeBytes(),
            session.fileSha256(),
            session.fileId(),
            parts.stream()
                .sorted(Comparator.comparingInt(GenericFileUploadPart::partNumber))
                .map(GenericFileUploadAssembler::toPartDto)
                .toList()
        );
    }

    private static GenericFileUploadPartDTO toPartDto(GenericFileUploadPart part) {
        return new GenericFileUploadPartDTO(
            part.partNumber(),
            part.partSha256(),
            part.sizeBytes(),
            part.etag(),
            null,
            null,
            Map.of()
        );
    }
}
