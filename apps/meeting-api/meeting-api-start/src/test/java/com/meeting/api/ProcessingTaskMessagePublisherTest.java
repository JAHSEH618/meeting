package com.meeting.api;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.infrastructure.mq.ProcessingTaskMessagePublisher;
import com.meeting.api.infrastructure.mq.RabbitMqPublisher;
import com.meeting.api.infrastructure.mq.RabbitMqProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskMessagePublisherTest {

    @Test
    void publishesProcessingTaskMessageWithRequiredHeaders() {
        CapturingRabbitMqPublisher rabbit = new CapturingRabbitMqPublisher();
        ProcessingTaskMessagePublisher publisher = new ProcessingTaskMessagePublisher(rabbit);

        publisher.publish(
            "task_01",
            "tenant_01",
            "trace_01",
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR),
            "{\"taskId\":\"task_01\"}"
        );

        assertThat(rabbit.routingKey).isEqualTo("task.audio-cpu");
        assertThat(rabbit.payloadJson).isEqualTo("{\"taskId\":\"task_01\"}");
        assertThat(rabbit.headers)
            .containsEntry("taskId", "task_01")
            .containsEntry("tenantId", "tenant_01")
            .containsEntry("traceId", "trace_01");
    }

    private static final class CapturingRabbitMqPublisher extends RabbitMqPublisher {
        private String routingKey;
        private String payloadJson;
        private Map<String, Object> headers;

        private CapturingRabbitMqPublisher() {
            super(new RabbitMqProperties("localhost", 5672, "guest", "guest", "/"));
        }

        @Override
        public void publish(String routingKey, String payloadJson, Map<String, Object> headers) {
            this.routingKey = routingKey;
            this.payloadJson = payloadJson;
            this.headers = headers;
        }
    }
}
