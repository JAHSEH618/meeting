package com.meeting.api.adapter.sse;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.client.enums.TaskEventType;
import com.meeting.api.client.export.ExportFacade;
import com.meeting.api.client.export.ExportJobDTO;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint for export job state changes.
 *
 * <p>Phase 8 hardening (final-check.md D1/D2) — emits an initial
 * {@link TaskEventType#EXPORT_STATUS_CHANGED} snapshot of the current
 * job. The frontend opens this stream on the exports page and keeps a
 * 3 s polling fallback for browsers without {@code EventSource}
 * support or transient network failures.
 *
 * <p>Snapshot-only semantics mirror {@link ProcessingTaskSseController}:
 * the runtime has no broker-backed event bus yet, so we send the latest
 * state, write the metric, and close the stream so the client can
 * reconnect on the next status check. Once a domain-event listener is
 * wired (see {@code todo.md} Phase 8 §2), this controller can keep the
 * emitter open and push subsequent updates without code changes from
 * the frontend.
 */
@RestController
public class ExportSseController {

    private final ExportFacade exportFacade;
    private final MeetingApiMetrics metrics;
    private final AtomicLong sequence = new AtomicLong(1);

    public ExportSseController(ExportFacade exportFacade, MeetingApiMetrics metrics) {
        this.exportFacade = exportFacade;
        this.metrics = metrics;
    }

    @GetMapping(value = "/api/exports/{exportId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @PathVariable String exportId,
        @RequestHeader(value = "Last-Event-Id", required = false) String lastEventId
    ) throws IOException {
        ExportJobDTO export = exportFacade.get(TenantContextHolder.currentTenantId(), exportId)
            .orElseThrow(() -> new IllegalArgumentException("export not found: " + exportId));

        SseEmitter emitter = new SseEmitter(120_000L);
        metrics.sseOpenedCounter().increment();
        long seq = sequence.getAndIncrement();
        String eventId = exportId + ":" + seq;

        emitter.send(SseEmitter.event()
            .id(eventId)
            .name(TaskEventType.EXPORT_STATUS_CHANGED.name())
            .data(snapshotPayload(export, eventId, seq)));
        metrics.sseEventCounter(TaskEventType.EXPORT_STATUS_CHANGED.name()).increment();

        // Close immediately — clients fall back to polling for subsequent
        // updates. A future domain-event listener can hold this open.
        emitter.complete();
        return emitter;
    }

    private static Map<String, Object> snapshotPayload(ExportJobDTO export, String eventId, long seq) {
        return Map.of(
            "eventId", eventId,
            "sequence", seq,
            "eventType", TaskEventType.EXPORT_STATUS_CHANGED.name(),
            "exportId", export.exportId(),
            "meetingId", export.meetingId(),
            "status", export.status().name(),
            "stale", export.stale(),
            "revoked", export.revoked(),
            "errorCode", export.errorCode() == null ? "" : export.errorCode(),
            "occurredAt", OffsetDateTime.now().toString()
        );
    }
}
