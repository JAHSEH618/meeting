package com.meeting.api.infrastructure.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class LocalOrMinioStorageCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String storageType = context.getEnvironment().getProperty("meeting.storage.type", "local");
        return "local".equalsIgnoreCase(storageType) || "minio".equalsIgnoreCase(storageType);
    }
}
