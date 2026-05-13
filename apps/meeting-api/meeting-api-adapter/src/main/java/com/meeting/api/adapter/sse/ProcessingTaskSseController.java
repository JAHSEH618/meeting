package com.meeting.api.adapter.sse;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.enums.TaskEventType;
import com.meeting.api.client.task.ProcessingTaskFacade;
import com.meeting.api.client.task.TaskEventDTO;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ProcessingTaskSseController {
    private final ProcessingTaskFacade processingTaskFacade;
    private final AtomicLong sequence = new AtomicLong(1);

    public ProcessingTaskSseController(ProcessingTaskFacade processingTaskFacade) {
        this.processingTaskFacade = processingTaskFacade;
    }

    @GetMapping(value = "/api/processing-tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @PathVariable String taskId,
        @RequestHeader(value = "Last-Event-Id", required = false) String lastEventId
    ) throws IOException {
        var task = processingTaskFacade.get(TenantContextHolder.currentTenantId(), taskId)
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        SseEmitter emitter = new SseEmitter(120_000L);
        long seq = sequence.getAndIncrement();
        TaskEventDTO event = new TaskEventDTO(
            taskId + ":" + seq,
            seq,
            TaskEventType.TASK_SNAPSHOT,
            task.taskId(),
            task.meetingId(),
            task.currentStep(),
            task.status().name(),
            task.phase(),
            null,
            task.retryable(),
            task.lastErrorCode(),
            OffsetDateTime.now(),
            task.attemptNo(),
            null,
            null,
            task.steps().stream().filter(step -> step.status().name().equals("SUCCEEDED")).map(step -> step.stepName().name()).toList(),
            task.steps(),
            task.leaseExpiresAt()
        );
        emitter.send(SseEmitter.event().id(event.eventId()).name(event.eventType().name()).data(event));
        return emitter;
    }
}
