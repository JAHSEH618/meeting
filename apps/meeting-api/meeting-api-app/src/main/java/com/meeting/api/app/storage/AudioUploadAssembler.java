package com.meeting.api.app.storage;

import com.meeting.api.client.storage.AudioUploadPartDTO;
import com.meeting.api.client.storage.AudioUploadSessionDTO;
import com.meeting.api.domain.storage.AudioUploadPart;
import com.meeting.api.domain.storage.AudioUploadSession;
import java.util.Comparator;
import java.util.List;

final class AudioUploadAssembler {
    private AudioUploadAssembler() {
    }

    static AudioUploadSessionDTO toDto(AudioUploadSession session, List<AudioUploadPart> parts) {
        return new AudioUploadSessionDTO(
            session.uploadId(),
            session.meetingId(),
            session.uploadStatus(),
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
                .sorted(Comparator.comparingInt(AudioUploadPart::partNumber))
                .map(AudioUploadAssembler::toPartDto)
                .toList()
        );
    }

    private static AudioUploadPartDTO toPartDto(AudioUploadPart part) {
        return new AudioUploadPartDTO(
            part.partNumber(),
            part.partSha256(),
            part.etag(),
            part.sizeBytes(),
            part.uploadStatus(),
            part.uploadedAt()
        );
    }
}
