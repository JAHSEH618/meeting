package com.meeting.api.domain.storage;

import java.util.Optional;

public interface MeetingFileRepository {
    MeetingFile save(MeetingFile file);

    Optional<MeetingFile> findById(String tenantId, String fileId);
}
