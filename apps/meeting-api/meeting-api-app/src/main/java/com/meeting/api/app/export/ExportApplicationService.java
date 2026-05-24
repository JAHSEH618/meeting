package com.meeting.api.app.export;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.export.CreateExportCommand;
import com.meeting.api.client.export.ExportFacade;
import com.meeting.api.client.export.ExportJobDTO;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import com.meeting.api.domain.export.ExportDownloadRevokedEvent;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportJobCompletedEvent;
import com.meeting.api.domain.export.ExportJobCreatedEvent;
import com.meeting.api.domain.export.ExportJobRepository;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.client.enums.ExportType;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the export lifecycle behind the {@code /api/...exports}
 * endpoints. The actual rendering is asynchronous and handled by the
 * {@code export-queue} consumer (phase 6.4) — this service only:
 *
 * <ol>
 *   <li>Validates the meeting exists and the caller's expected versions
 *       still match (returns {@code EXPORT_CONTENT_STALE} otherwise);</li>
 *   <li>Checks {@link LegalHoldCheckPort} — returns {@code LEGAL_HOLD_BLOCKED}
 *       if the meeting is protected;</li>
 *   <li>Persists a fresh {@link ExportJob} (status=QUEUED) and writes
 *       {@link ExportJobCreatedEvent} to the outbox in the same
 *       transaction (caller will pick up via {@code export-queue}).</li>
 * </ol>
 *
 * <p>Read-side: {@code get} and {@code listByMeeting} also stamp a
 * derived {@code stale} flag at read time by comparing the job's
 * input versions to the meeting's current versions. Cancel and revoke
 * are simple state-machine transitions on the aggregate plus an audit
 * outbox event.
 */
@Service
public class ExportApplicationService implements ExportFacade {

    private static final Logger log = LoggerFactory.getLogger(ExportApplicationService.class);

    private final TenantScopedTransaction tenantTx;
    private final ExportJobRepository exportRepo;
    private final MeetingRepository meetingRepo;
    private final MeetingFileRepository meetingFileRepo;
    private final ObjectStorageGateway storage;
    private final LegalHoldCheckPort legalHoldCheck;
    private final MessagePublisher messagePublisher;
    private final Clock clock;
    private final long downloadTtlSeconds;

    public ExportApplicationService(
        TenantScopedTransaction tenantTx,
        ExportJobRepository exportRepo,
        MeetingRepository meetingRepo,
        MeetingFileRepository meetingFileRepo,
        ObjectStorageGateway storage,
        LegalHoldCheckPort legalHoldCheck,
        MessagePublisher messagePublisher,
        @Value("${meeting.export.download-ttl-hours:24}") long downloadTtlHours
    ) {
        this(tenantTx, exportRepo, meetingRepo, meetingFileRepo, storage,
             legalHoldCheck, messagePublisher,
             Clock.systemUTC(), Duration.ofHours(downloadTtlHours).toSeconds());
    }

    public ExportApplicationService(
        TenantScopedTransaction tenantTx,
        ExportJobRepository exportRepo,
        MeetingRepository meetingRepo,
        MeetingFileRepository meetingFileRepo,
        ObjectStorageGateway storage,
        LegalHoldCheckPort legalHoldCheck,
        MessagePublisher messagePublisher,
        Clock clock,
        long downloadTtlSeconds
    ) {
        this.tenantTx = tenantTx;
        this.exportRepo = exportRepo;
        this.meetingRepo = meetingRepo;
        this.meetingFileRepo = meetingFileRepo;
        this.storage = storage;
        this.legalHoldCheck = legalHoldCheck;
        this.messagePublisher = messagePublisher;
        this.clock = clock;
        this.downloadTtlSeconds = downloadTtlSeconds;
    }

    @Override
    public ExportJobDTO create(CreateExportCommand cmd) {
        return tenantTx.execute(cmd.tenantId(), cmd.createdBy(), cmd.requestId(), () -> {
            Meeting meeting = meetingRepo.findById(cmd.tenantId(), cmd.meetingId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 404,
                    "meeting not found: " + cmd.meetingId(), false
                ));

            if (legalHoldCheck.isProtected(cmd.tenantId(), "MEETING", cmd.meetingId())) {
                log.info(
                    "export_create_blocked_legal_hold tenant={} meeting={} user={}",
                    cmd.tenantId(), cmd.meetingId(), cmd.createdBy()
                );
                throw new ApplicationException(
                    ErrorCode.LEGAL_HOLD_BLOCKED, 423,
                    "meeting is under legal hold: " + cmd.meetingId(), false
                );
            }

            if (meeting.transcriptVersion() != cmd.expectedTranscriptVersion()) {
                log.info(
                    "export_create_stale tenant={} meeting={} expected={} actual={}",
                    cmd.tenantId(), cmd.meetingId(),
                    cmd.expectedTranscriptVersion(), meeting.transcriptVersion()
                );
                throw new ApplicationException(
                    ErrorCode.EXPORT_CONTENT_STALE, 422,
                    "transcript moved past requested version "
                        + cmd.expectedTranscriptVersion() + " (now "
                        + meeting.transcriptVersion() + ")",
                    false
                );
            }
            if (cmd.expectedMinutesVersion() != null
                && meeting.minutesVersion() != cmd.expectedMinutesVersion()) {
                log.info(
                    "export_create_stale_minutes tenant={} meeting={} expected={} actual={}",
                    cmd.tenantId(), cmd.meetingId(),
                    cmd.expectedMinutesVersion(), meeting.minutesVersion()
                );
                throw new ApplicationException(
                    ErrorCode.EXPORT_CONTENT_STALE, 422,
                    "minutes moved past requested version", false
                );
            }

            OffsetDateTime now = OffsetDateTime.now(clock);
            String exportId = "exp_" + UUID.randomUUID().toString().replace("-", "");
            ExportJob job = ExportJob.builder()
                .id(exportId)
                .tenantId(cmd.tenantId())
                .meetingId(cmd.meetingId())
                .exportType(ExportType.MEETING)
                .format(cmd.format())
                .inputTranscriptVersion(meeting.transcriptVersion())
                .inputMinutesVersion(meeting.minutesVersion())
                .watermarkText(cmd.watermarkText())
                .renderOptions(cmd.renderOptions())
                .createdBy(cmd.createdBy())
                .createdAt(now)
                .build();
            exportRepo.save(job);

            messagePublisher.publish(new ExportJobCreatedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                cmd.tenantId(),
                exportId,
                cmd.meetingId(),
                cmd.format(),
                meeting.transcriptVersion(),
                meeting.minutesVersion() == 0 ? null : meeting.minutesVersion(),
                /* sequenceNo placeholder — set by the publisher when the row is acquired */ 1L,
                now,
                cmd.requestId()
            ));

            log.info(
                "export_created tenant={} export={} meeting={} format={} transcriptV={} minutesV={}",
                cmd.tenantId(), exportId, cmd.meetingId(), cmd.format(),
                meeting.transcriptVersion(), meeting.minutesVersion()
            );
            return toDto(job, /* stale */ false);
        });
    }

    @Override
    public Optional<ExportJobDTO> get(String tenantId, String exportId) {
        return tenantTx.execute(tenantId, null, null, () ->
            exportRepo.findById(tenantId, exportId).map(job -> {
                boolean stale = computeStale(tenantId, job);
                return toDto(job, stale);
            })
        );
    }

    @Override
    public PageResult<ExportJobDTO> listByMeeting(
        String tenantId, String meetingId, String cursor, int limit
    ) {
        return tenantTx.execute(tenantId, null, null, () -> {
            PageResult<ExportJob> page = exportRepo.listByMeeting(tenantId, meetingId, cursor, limit);
            return new PageResult<>(
                page.items().stream()
                    .map(job -> toDto(job, computeStale(tenantId, job)))
                    .toList(),
                page.page()
            );
        });
    }

    @Override
    public void cancel(String tenantId, String exportId, String userId) {
        tenantTx.executeWithoutResult(tenantId, userId, null, () -> {
            ExportJob job = exportRepo.findById(tenantId, exportId)
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 404,
                    "export not found: " + exportId, false
                ));
            if (job.status().isTerminal()) {
                throw new ApplicationException(
                    ErrorCode.EXPORT_ALREADY_FINISHED, 409,
                    "cannot cancel terminal export " + exportId
                        + " (status=" + job.status() + ")",
                    false
                );
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            job.markCancelled(now);
            exportRepo.update(job);
            publishCompletion(job, now);
            log.info(
                "export_cancelled tenant={} export={} meeting={} by={}",
                tenantId, exportId, job.meetingId(), userId
            );
        });
    }

    @Override
    public void revokeLink(String tenantId, String exportId, String userId) {
        tenantTx.executeWithoutResult(tenantId, userId, null, () -> {
            ExportJob job = exportRepo.findById(tenantId, exportId)
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 404,
                    "export not found: " + exportId, false
                ));
            OffsetDateTime now = OffsetDateTime.now(clock);
            job.revokeDownload(now);
            exportRepo.update(job);
            messagePublisher.publish(new ExportDownloadRevokedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                tenantId, exportId, job.meetingId(), userId, 1L, now
            ));
            log.info(
                "export_link_revoked tenant={} export={} meeting={} by={}",
                tenantId, exportId, job.meetingId(), userId
            );
        });
    }

    /** Compute the read-time staleness flag from the live meeting version. */
    private boolean computeStale(String tenantId, ExportJob job) {
        if (job.meetingId() == null) return false;
        return meetingRepo.findById(tenantId, job.meetingId())
            .map(m -> {
                if (job.inputTranscriptVersion() != null
                    && m.transcriptVersion() != job.inputTranscriptVersion()) {
                    return true;
                }
                if (job.inputMinutesVersion() != null
                    && m.minutesVersion() != job.inputMinutesVersion()) {
                    return true;
                }
                return false;
            })
            .orElse(false);
    }

    private void publishCompletion(ExportJob job, OffsetDateTime at) {
        messagePublisher.publish(new ExportJobCompletedEvent(
            "evt_" + UUID.randomUUID().toString().replace("-", ""),
            job.tenantId(),
            job.id(),
            job.meetingId(),
            job.status(),
            job.fileId(),
            job.fileHash(),
            job.errorCode() == null ? null : job.errorCode().name(),
            1L,
            at
        ));
    }

    /**
     * Build the DTO. {@code downloadUrl} is filled by presigning the
     * underlying {@code meeting_files} object when the export is
     * SUCCEEDED, the download isn't revoked, and the file row is still
     * findable. REVOKED jobs and STALE results explicitly return {@code null}.
     */
    private ExportJobDTO toDto(ExportJob job, boolean stale) {
        String downloadUrl = null;
        Long fileSizeBytes = null;
        boolean revoked = job.status() == ExportStatus.REVOKED
            || job.downloadRevokedAt() != null;
        if (job.status() == ExportStatus.SUCCEEDED
            && !revoked
            && job.fileId() != null) {
            OffsetDateTime expiresAt = job.downloadExpiresAt() == null
                ? OffsetDateTime.now(clock).plusSeconds(downloadTtlSeconds)
                : job.downloadExpiresAt();
            MeetingFile file = meetingFileRepo.findById(job.tenantId(), job.fileId())
                .orElse(null);
            if (file != null) {
                downloadUrl = storage
                    .presignGet(file.bucket(), file.objectKey(), expiresAt)
                    .url();
                fileSizeBytes = file.sizeBytes();
            }
        }
        return new ExportJobDTO(
            job.id(),
            job.meetingId(),
            job.status(),
            job.format(),
            job.dataBoundaryMode(),
            job.inputTranscriptVersion(),
            job.inputMinutesVersion(),
            job.snapshotManifestId(),
            job.watermarkText(),
            downloadUrl,
            job.downloadExpiresAt(),
            job.fileHash(),
            fileSizeBytes,
            revoked,
            stale,
            job.errorCode() == null ? null : job.errorCode().name(),
            job.downloadExpiresAt() == null
                ? job.createdAt().plusSeconds(downloadTtlSeconds)
                : job.downloadExpiresAt(),
            job.createdAt(),
            job.finishedAt()
        );
    }
}
