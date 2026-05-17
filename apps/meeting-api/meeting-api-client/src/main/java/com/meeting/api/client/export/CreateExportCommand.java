package com.meeting.api.client.export;

import com.meeting.api.client.enums.ExportFormat;
import java.util.Objects;

/**
 * Command for {@code POST /api/meetings/{meetingId}/exports}. The
 * caller's {@code expectedTranscriptVersion} / {@code expectedMinutesVersion}
 * snapshot the content the user is asking to render — if the
 * meeting has since moved past those, the application service rejects
 * with {@code EXPORT_CONTENT_STALE}.
 */
public record CreateExportCommand(
    String tenantId,
    String meetingId,
    ExportFormat format,
    int expectedTranscriptVersion,
    Integer expectedMinutesVersion,
    String watermarkText,
    ExportRenderOptions renderOptions,
    String createdBy,
    String requestId,
    String traceId
) {

    public CreateExportCommand {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(meetingId, "meetingId");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(createdBy, "createdBy");
        if (tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (meetingId.isBlank()) throw new IllegalArgumentException("meetingId must not be blank");
        if (createdBy.isBlank()) throw new IllegalArgumentException("createdBy must not be blank");
        if (expectedTranscriptVersion < 0) {
            throw new IllegalArgumentException("expectedTranscriptVersion must be >= 0");
        }
        if (expectedMinutesVersion != null && expectedMinutesVersion < 0) {
            throw new IllegalArgumentException("expectedMinutesVersion must be >= 0 when present");
        }
        if (watermarkText != null && watermarkText.length() > 200) {
            throw new IllegalArgumentException("watermarkText must be <= 200 chars");
        }
        if (renderOptions == null) {
            renderOptions = ExportRenderOptions.defaults();
        }
    }
}
