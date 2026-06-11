package com.meeting.api.client.storage;

public record GenericFileCompleteDTO(
    String fileId,
    String sha256,
    long sizeBytes,
    String contentType
) {
}
