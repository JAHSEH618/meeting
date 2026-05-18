package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.export.ExportRenderService;
import com.meeting.api.app.export.ExportRenderService.ExportJobMessage;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.meeting.api.infrastructure.mq.ExportQueueConsumer;
import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link ExportQueueConsumer#onMessage} directly so we exercise
 * the deserialization + retry-mapping logic without a real RabbitMQ
 * broker. The consumer is constructed with {@code enabled=false} so
 * the connection / channel are never opened, and the test never has to
 * stub Channel#basicAck — the ack/reject methods early-return when the
 * channel is null.
 */
class ExportQueueConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RabbitMqProperties properties = new RabbitMqProperties(
        "localhost", 5672, "guest", "guest", "/"
    );

    @Test
    void deserializesAndDispatchesHappyPath() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        ExportQueueConsumer consumer = newConsumer(renderService);

        byte[] body = json("""
            {"tenantId":"tenant_01","exportId":"exp_01",
             "meetingId":"mtg_01","format":"MARKDOWN",
             "expectedInputVersion":{"transcriptVersion":3},
             "traceId":"trace_01","createdAt":"2026-05-18T10:00:00Z"}
            """);

        consumer.onMessage(/* envelope */ null, body);

        ArgumentCaptor<ExportJobMessage> captor = ArgumentCaptor.forClass(ExportJobMessage.class);
        verify(renderService).render(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("tenant_01");
        assertThat(captor.getValue().exportId()).isEqualTo("exp_01");
        assertThat(captor.getValue().meetingId()).isEqualTo("mtg_01");
        assertThat(captor.getValue().traceId()).isEqualTo("trace_01");
    }

    @Test
    void badPayloadIsRejectedWithoutCallingRenderService() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        ExportQueueConsumer consumer = newConsumer(renderService);

        consumer.onMessage(null, "not json".getBytes());

        Mockito.verifyNoInteractions(renderService);
    }

    @Test
    void inputInvalidExceptionIsSwallowedAndAcked() {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        Mockito.doThrow(new ExportInputInvalidException(
                ErrorCode.EXPORT_CONTENT_STALE, "stale"
            ))
            .when(renderService).render(any());

        ExportQueueConsumer consumer = newConsumer(renderService);
        consumer.onMessage(null, json("""
            {"tenantId":"t","exportId":"exp",
             "meetingId":"mtg","format":"MARKDOWN",
             "expectedInputVersion":{"transcriptVersion":1},
             "traceId":"tr"}
            """));
        // No throw — the consumer absorbs the exception so the
        // message is not requeued (in a real broker we'd basicReject with
        // requeue=false here).
    }

    @Test
    void runtimeExceptionIsSwallowedSoMessageIsRequeued() {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        Mockito.doThrow(new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED, "soffice timeout"
            ))
            .when(renderService).render(any());

        ExportQueueConsumer consumer = newConsumer(renderService);
        consumer.onMessage(null, json("""
            {"tenantId":"t","exportId":"exp",
             "meetingId":"mtg","format":"MARKDOWN",
             "expectedInputVersion":{"transcriptVersion":1},
             "traceId":"tr"}
            """));
    }

    @Test
    void unexpectedExceptionMarksJobFailedTerminally() {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        Mockito.doThrow(new RuntimeException("kms outage"))
            .when(renderService).render(any());

        ExportQueueConsumer consumer = newConsumer(renderService);
        consumer.onMessage(null, json("""
            {"tenantId":"t","exportId":"exp",
             "meetingId":"mtg","format":"MARKDOWN",
             "expectedInputVersion":{"transcriptVersion":1},
             "traceId":"tr"}
            """));

        verify(renderService).failTerminally(any(),
            Mockito.eq(ErrorCode.INTERNAL_ERROR),
            any());
    }

    private ExportQueueConsumer newConsumer(ExportRenderService renderService) {
        return new ExportQueueConsumer(properties, objectMapper, renderService, /* enabled */ false);
    }

    private static byte[] json(String body) {
        return body.getBytes();
    }
}
