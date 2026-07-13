package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.export.ExportRenderService;
import com.meeting.api.app.export.ExportRenderService.ExportJobMessage;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.domain.export.ExportRuntimeException;
import com.meeting.api.infrastructure.mq.ExportQueueConsumer;
import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

        // First attempt is retryable — the job must NOT be failed terminally.
        verify(renderService, never()).failTerminally(any(), any(), any());
    }

    @Test
    void retryableFailureBelowAttemptCapIsRequeued() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        Mockito.doThrow(new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED, "soffice timeout"
            ))
            .when(renderService).render(any());

        ExportQueueConsumer consumer = newConsumer(renderService);
        Channel channel = attachMockChannel(consumer);

        // x-delivery-count=3 → attempt 4 of 5 → still retryable.
        consumer.onMessage(
            envelope(7L),
            propertiesWithDeliveryCount(3L),
            exportJson()
        );

        verify(channel).basicReject(7L, /* requeue */ true);
        verify(renderService, never()).failTerminally(any(), any(), any());
    }

    @Test
    void retryableFailureAtAttemptCapFailsJobTerminallyAndDeadLetters() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        Mockito.doThrow(new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED, "soffice timeout"
            ))
            .when(renderService).render(any());

        ExportQueueConsumer consumer = newConsumer(renderService);
        Channel channel = attachMockChannel(consumer);

        // x-delivery-count=4 → attempt 5 of 5 → retries exhausted.
        consumer.onMessage(
            envelope(9L),
            propertiesWithDeliveryCount(4L),
            exportJson()
        );

        verify(renderService).failTerminally(
            any(),
            eq(ErrorCode.EXPORT_RENDER_FAILED),
            contains("after 5 deliveries")
        );
        // reject WITHOUT requeue so the message dead-letters to export-queue.dlq
        // instead of spinning the consumer forever.
        verify(channel).basicReject(9L, /* requeue */ false);
        verify(channel, never()).basicReject(anyLongTag(), eq(true));
    }

    @Test
    void missingDeliveryCountHeaderCountsAsFirstAttempt() throws Exception {
        ExportRenderService renderService = Mockito.mock(ExportRenderService.class);
        Mockito.doThrow(new ExportRuntimeException(
                ErrorCode.EXPORT_RENDER_FAILED, "soffice timeout"
            ))
            .when(renderService).render(any());

        ExportQueueConsumer consumer = newConsumer(renderService);
        Channel channel = attachMockChannel(consumer);

        consumer.onMessage(envelope(3L), null, exportJson());

        verify(channel).basicReject(3L, /* requeue */ true);
        verify(renderService, never()).failTerminally(any(), any(), any());
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
        return new ExportQueueConsumer(
            properties, objectMapper, renderService, /* enabled */ false, /* maxAttempts */ 5
        );
    }

    /**
     * The consumer only opens a channel via {@code start()} against a live
     * broker; inject a mock through the private field so unit tests can pin
     * the ack/reject calls without a broker.
     */
    private static Channel attachMockChannel(ExportQueueConsumer consumer) throws Exception {
        Channel channel = Mockito.mock(Channel.class);
        var field = ExportQueueConsumer.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(consumer, channel);
        return channel;
    }

    private static Envelope envelope(long deliveryTag) {
        return new Envelope(deliveryTag, /* redeliver */ true, "", ExportQueueConsumer.QUEUE_NAME);
    }

    private static AMQP.BasicProperties propertiesWithDeliveryCount(long deliveryCount) {
        return new AMQP.BasicProperties.Builder()
            .headers(Map.of("x-delivery-count", deliveryCount))
            .build();
    }

    private static byte[] exportJson() {
        return json("""
            {"tenantId":"t","exportId":"exp",
             "meetingId":"mtg","format":"MARKDOWN",
             "expectedInputVersion":{"transcriptVersion":1},
             "traceId":"tr"}
            """);
    }

    private static long anyLongTag() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static byte[] json(String body) {
        return body.getBytes();
    }
}
