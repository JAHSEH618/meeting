package com.meeting.api.app.export;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportGatewayRegistry;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportJobCompletedEvent;
import com.meeting.api.domain.export.ExportJobRepository;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.meeting.api.domain.export.MeetingSnapshotPort;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;
import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Renders and persists a single {@link ExportJob} that has reached the
 * {@code export-queue} consumer. The work is broken into three short
 * transactions (CLAUDE.md invariant 11):
 *
 * <ol>
 *   <li><b>Short TX #1 — markRunning</b>. Load the row by id, transition
 *       QUEUED→RUNNING, persist. Idempotent on RUNNING for retries; if
 *       the job is already terminal we exit fast.</li>
 *   <li><b>No TX — render + upload</b>. Load the version-locked
 *       {@link MeetingSnapshot}, hand it to the matching
 *       {@link ExportGateway}, and {@code putObject} the bytes into the
 *       configured exports bucket.</li>
 *   <li><b>Short TX #2 — markSucceeded</b>. Insert a {@code meeting_files}
 *       row for the rendered artefact, transition RUNNING→SUCCEEDED with
 *       file id / hash / expiry, and emit
 *       {@link ExportJobCompletedEvent} to the outbox so SSE listeners
 *       fan out the status change.</li>
 * </ol>
 *
 * <p>Failure mapping (matches plan 6.4.2.d):
 * <ul>
 *   <li>{@link ExportInputInvalidException} — non-retryable. Job is
 *       marked FAILED and the consumer acknowledges the message.</li>
 *   <li>{@link ExportRuntimeException} or any other unchecked exception
 *       — the consumer rethrows so the message is requeued (or
 *       eventually lands in the DLQ).</li>
 * </ul>
 *
 * <p>This service is intentionally framework-light so it can be driven
 * by both a real RabbitMQ consumer ({@code ExportQueueConsumer}) and a
 * unit test with stub ports.
 */
@Service
public class ExportRenderService {

    private static final Logger log = LoggerFactory.getLogger(ExportRenderService.class);

    private final TenantScopedTransaction tenantTx;
    private final ExportJobRepository exportRepo;
    private final MeetingFileRepository meetingFileRepo;
    private final MeetingSnapshotPort snapshotPort;
    private final ExportGatewayRegistry gatewayRegistry;
    private final ObjectStorageGateway storage;
    private final MessagePublisher messagePublisher;
    private final Clock clock;
    private final String exportsBucket;
    private final long downloadTtlSeconds;

    public ExportRenderService(
        TenantScopedTransaction tenantTx,
        ExportJobRepository exportRepo,
        MeetingFileRepository meetingFileRepo,
        MeetingSnapshotPort snapshotPort,
        ExportGatewayRegistry gatewayRegistry,
        ObjectStorageGateway storage,
        MessagePublisher messagePublisher,
        @Value("${meeting.storage.bucket-exports:meeting-exports}") String exportsBucket,
        @Value("${meeting.export.download-ttl-hours:24}") long downloadTtlHours
    ) {
        this(tenantTx, exportRepo, meetingFileRepo, snapshotPort, gatewayRegistry,
             storage, messagePublisher, Clock.systemUTC(), exportsBucket,
             Duration.ofHours(downloadTtlHours).toSeconds());
    }

    public ExportRenderService(
        TenantScopedTransaction tenantTx,
        ExportJobRepository exportRepo,
        MeetingFileRepository meetingFileRepo,
        MeetingSnapshotPort snapshotPort,
        ExportGatewayRegistry gatewayRegistry,
        ObjectStorageGateway storage,
        MessagePublisher messagePublisher,
        Clock clock,
        String exportsBucket,
        long downloadTtlSeconds
    ) {
        this.tenantTx = tenantTx;
        this.exportRepo = exportRepo;
        this.meetingFileRepo = meetingFileRepo;
        this.snapshotPort = snapshotPort;
        this.gatewayRegistry = gatewayRegistry;
        this.storage = storage;
        this.messagePublisher = messagePublisher;
        this.clock = clock;
        this.exportsBucket = exportsBucket;
        this.downloadTtlSeconds = downloadTtlSeconds;
    }

    public RenderOutcome render(ExportJobMessage msg) {
        // Short TX #1: claim row + markRunning (idempotent on RUNNING).
        ExportJob job = tenantTx.execute(msg.tenantId(), "system:export-consumer", msg.traceId(), () -> {
            ExportJob loaded = exportRepo.findById(msg.tenantId(), msg.exportId())
                .orElseThrow(() -> new ExportInputInvalidException(
                    ErrorCode.VALIDATION_FAILED,
                    "export not found: " + msg.exportId()
                ));
            if (loaded.status().isTerminal()) {
                log.info(
                    "export_render_skipped_terminal tenant={} export={} status={}",
                    msg.tenantId(), msg.exportId(), loaded.status()
                );
                return loaded;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            loaded.markRunning(now);
            exportRepo.update(loaded);
            return loaded;
        });

        if (job.status() != ExportStatus.RUNNING) {
            return new RenderOutcome(job.status(), null);
        }

        // No TX: snapshot + render + upload. Failures bubble out and the
        // consumer decides retry vs. DLQ. ExportInputInvalidException is
        // caught here so we can mark the job FAILED before re-throwing.
        try {
            MeetingSnapshot snapshot = snapshotPort.loadSnapshot(
                job.tenantId(), job.meetingId(),
                job.inputTranscriptVersion() == null ? 0 : job.inputTranscriptVersion(),
                job.inputMinutesVersion()
            ).orElseThrow(() -> new ExportInputInvalidException(
                ErrorCode.EXPORT_CONTENT_STALE,
                "snapshot version no longer ACTIVE for export " + job.id()
            ));
            ExportGateway.RenderedFile rendered = gatewayRegistry
                .gateway(job.format())
                .render(job, snapshot);

            String objectKey = objectKeyFor(job, rendered);
            String shortSha = shortSha(rendered.sha256());
            StorageObject persisted = storage.putObject(
                exportsBucket, objectKey, rendered.bytes(),
                contentTypeFor(job), shortSha
            );

            // Short TX #2: persist meeting_files row + markSucceeded +
            // outbox completion event.
            return tenantTx.execute(msg.tenantId(), "system:export-consumer", msg.traceId(), () -> {
                OffsetDateTime now = OffsetDateTime.now(clock);
                String fileId = "mf_" + UUID.randomUUID().toString().replace("-", "");
                String uri = "oss://" + persisted.bucket() + "/" + persisted.objectKey();
                MeetingFile saved = meetingFileRepo.save(new MeetingFile(
                    fileId,
                    job.tenantId(),
                    job.meetingId(),
                    "EXPORT",
                    "MEETING_EXPORT_" + job.format().name(),
                    fileNameFor(job),
                    contentTypeFor(job),
                    persisted.bucket(),
                    persisted.objectKey(),
                    uri,
                    persisted.sizeBytes(),
                    shortSha,
                    /* durationMs */ null,
                    "READY",
                    job.createdBy(),
                    now,
                    now
                ));
                OffsetDateTime expiresAt = now.plusSeconds(downloadTtlSeconds);
                job.markSucceeded(saved.fileId(), shortSha, expiresAt, now);
                exportRepo.update(job);

                messagePublisher.publish(new ExportJobCompletedEvent(
                    "evt_" + UUID.randomUUID().toString().replace("-", ""),
                    job.tenantId(),
                    job.id(),
                    job.meetingId(),
                    job.status(),
                    job.fileId(),
                    job.fileHash(),
                    /* errorCode */ null,
                    /* sequenceNo placeholder */ 1L,
                    now
                ));
                log.info(
                    "export_succeeded tenant={} export={} file={} bytes={} sha256={}",
                    job.tenantId(), job.id(), saved.fileId(), persisted.sizeBytes(), shortSha
                );
                return new RenderOutcome(job.status(), saved.fileId());
            });
        } catch (ExportInputInvalidException ex) {
            markFailedShortTx(msg, job, ex.errorCode(), ex.getMessage());
            throw ex;
        } catch (ExportRuntimeException ex) {
            log.warn(
                "export_render_failed_retryable tenant={} export={} reason={}",
                msg.tenantId(), msg.exportId(), ex.getMessage()
            );
            // Don't mark FAILED — the message will be retried; only
            // after retry exhaustion should the consumer call
            // markFailed via failTerminally().
            throw ex;
        }
    }

    /**
     * Called by the consumer when retries are exhausted (e.g. final DLQ
     * delivery). Transitions the job to FAILED so the user sees a
     * terminal state instead of perpetual RUNNING.
     */
    public void failTerminally(ExportJobMessage msg, ErrorCode code, String reason) {
        markFailedShortTx(msg, null, code, reason);
    }

    private void markFailedShortTx(ExportJobMessage msg, ExportJob hint, ErrorCode code, String reason) {
        tenantTx.executeWithoutResult(msg.tenantId(), "system:export-consumer", msg.traceId(), () -> {
            ExportJob job = hint != null
                ? hint
                : exportRepo.findById(msg.tenantId(), msg.exportId()).orElse(null);
            if (job == null || job.status().isTerminal()) {
                return;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            try {
                job.markFailed(code, now);
            } catch (IllegalStateException ignored) {
                // Race: a parallel cancel beat us — leave the row alone.
                return;
            }
            exportRepo.update(job);
            messagePublisher.publish(new ExportJobCompletedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                job.tenantId(),
                job.id(),
                job.meetingId(),
                job.status(),
                null,
                null,
                code.name(),
                1L,
                now
            ));
            log.warn(
                "export_marked_failed tenant={} export={} code={} reason={}",
                msg.tenantId(), msg.exportId(), code, reason
            );
        });
    }

    private String objectKeyFor(ExportJob job, ExportGateway.RenderedFile rf) {
        String ext = extFor(job);
        return "tenant/" + job.tenantId()
            + "/meeting/" + job.meetingId()
            + "/export/" + job.id() + "/file." + ext;
    }

    private String fileNameFor(ExportJob job) {
        return job.id() + "." + extFor(job);
    }

    private static String extFor(ExportJob job) {
        return switch (job.format()) {
            case MARKDOWN -> "md";
            case DOCX -> "docx";
            case PDF -> "pdf";
        };
    }

    private static String contentTypeFor(ExportJob job) {
        return switch (job.format()) {
            case MARKDOWN -> "text/markdown; charset=utf-8";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case PDF -> "application/pdf";
        };
    }

    /** Strip an optional "sha256:" prefix and keep the hex digest. */
    private static String shortSha(String input) {
        if (input == null) return null;
        return input.startsWith("sha256:") ? input.substring("sha256:".length()) : input;
    }

    /** Outcome of a render call — exposed for unit tests and the consumer. */
    public record RenderOutcome(ExportStatus finalStatus, String fileId) {}

    /**
     * Adapter-friendly message shape — populated from the
     * {@code export-job-message.schema.json} JSON body or directly in
     * tests. The consumer is responsible for deserialization.
     */
    public record ExportJobMessage(
        String tenantId,
        String exportId,
        String meetingId,
        String traceId
    ) {
        public ExportJobMessage {
            if (tenantId == null || tenantId.isBlank()) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 400,
                    "ExportJobMessage.tenantId required", false
                );
            }
            if (exportId == null || exportId.isBlank()) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 400,
                    "ExportJobMessage.exportId required", false
                );
            }
        }
    }
}
