package com.meeting.api.infrastructure.mq;

import com.meeting.api.client.enums.ProcessingStep;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProcessingTaskMessagePublisher {
    private final RabbitMqPublisher rabbitMqPublisher;

    public ProcessingTaskMessagePublisher(RabbitMqPublisher rabbitMqPublisher) {
        this.rabbitMqPublisher = rabbitMqPublisher;
    }

    public void publish(
        String taskId,
        String tenantId,
        String traceId,
        List<ProcessingStep> pipelineSteps,
        String payloadJson
    ) {
        rabbitMqPublisher.publish(
            TaskRouting.routingKeyFor(pipelineSteps),
            payloadJson,
            Map.of(
                "taskId", taskId,
                "tenantId", tenantId,
                "traceId", traceId,
                "pipelineSteps", pipelineSteps.stream().map(Enum::name).toList()
            )
        );
    }
}
