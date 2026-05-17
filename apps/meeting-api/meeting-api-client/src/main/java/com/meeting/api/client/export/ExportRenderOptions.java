package com.meeting.api.client.export;

/**
 * Request-time options that drive what an {@code ExportGateway} renders.
 *
 * <p>These flags are persisted on the {@code ExportJob} so that a retry
 * by the queue consumer reproduces the same content. They are <em>not</em>
 * security boundaries — the underlying authoritative content access is
 * checked by the application layer before the snapshot is loaded.
 */
public record ExportRenderOptions(
    boolean includeTranscript,
    boolean includeMinutes,
    boolean includeItems,
    boolean includeSpeakers
) {

    /** Defaults: include every section. */
    public static ExportRenderOptions defaults() {
        return new ExportRenderOptions(true, true, true, true);
    }
}
