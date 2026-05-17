package com.meeting.api.domain.export;

import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.domain.export.MeetingSnapshotPort.MeetingSnapshot;

/**
 * Strategy port for rendering an {@link ExportJob} into a binary
 * artifact. Each {@link ExportFormat} has exactly one implementation
 * registered as a Spring {@code @Component}; an
 * {@code ExportGatewayRegistry} routes by {@link #supportedFormat()}.
 *
 * <p>Implementations must be pure: they do not write to TOS, do not
 * mutate the {@link ExportJob}, and do not open DB transactions.
 * The queue consumer is responsible for persistence after a successful
 * render.
 */
public interface ExportGateway {

    /** The single format this gateway handles. */
    ExportFormat supportedFormat();

    /**
     * Render the job into bytes. Implementations should not depend on
     * any thread-bound context beyond {@link MeetingSnapshot}.
     *
     * @throws ExportRuntimeException     retryable transient failures (timeouts, OS calls)
     * @throws ExportInputInvalidException non-retryable schema / shape failures
     */
    RenderedFile render(ExportJob job, MeetingSnapshot snapshot);

    /** Output of {@link #render}. The {@code sha256} is hex-encoded. */
    record RenderedFile(byte[] bytes, String sha256, long sizeBytes) {

        public RenderedFile {
            java.util.Objects.requireNonNull(bytes, "bytes");
            java.util.Objects.requireNonNull(sha256, "sha256");
            if (bytes.length != sizeBytes) {
                throw new IllegalArgumentException(
                    "sizeBytes must equal bytes.length; got " + sizeBytes + " vs " + bytes.length
                );
            }
        }
    }
}
