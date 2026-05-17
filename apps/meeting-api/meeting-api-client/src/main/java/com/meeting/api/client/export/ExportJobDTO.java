package com.meeting.api.client.export;

import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import java.time.OffsetDateTime;

/**
 * DTO returned by the export endpoints. Mirrors the OpenAPI
 * {@code ExportJobResponse.data} schema (additive only — older clients
 * may ignore new fields).
 *
 * <p>{@code downloadUrl} is non-null only when the job is
 * {@link ExportStatus#SUCCEEDED} and the link has not been revoked.
 * {@code stale} is computed at read time by comparing the snapshot's
 * input versions to the meeting's current versions, so the same job
 * row can flip stale → true after a transcript edit.
 */
public record ExportJobDTO(
    String exportId,
    String meetingId,
    ExportStatus status,
    ExportFormat format,
    ExportDataBoundaryMode dataBoundaryMode,
    Integer inputTranscriptVersion,
    Integer inputMinutesVersion,
    String snapshotManifestId,
    String watermarkText,
    String downloadUrl,
    OffsetDateTime downloadUrlExpiresAt,
    String sha256,
    Long fileSizeBytes,
    boolean revoked,
    boolean stale,
    String errorCode,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime finishedAt
) {}
