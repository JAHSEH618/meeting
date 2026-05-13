package com.meeting.api.domain.task;

import com.meeting.api.domain.common.DomainEvent;

public interface MessagePublisher {
    void publish(DomainEvent event);
}
