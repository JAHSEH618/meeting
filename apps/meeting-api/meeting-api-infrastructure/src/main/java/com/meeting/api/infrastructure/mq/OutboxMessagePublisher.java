package com.meeting.api.infrastructure.mq;

import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.task.MessagePublisher;
import org.springframework.stereotype.Component;

@Component
public class OutboxMessagePublisher implements MessagePublisher {
    private final OutboxEventStore outboxEventStore;

    public OutboxMessagePublisher(OutboxEventStore outboxEventStore) {
        this.outboxEventStore = outboxEventStore;
    }

    @Override
    public void publish(DomainEvent event) {
        outboxEventStore.append(event);
    }
}
