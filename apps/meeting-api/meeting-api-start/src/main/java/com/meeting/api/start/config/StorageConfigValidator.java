package com.meeting.api.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 存储配置校验器：确保本地模式必须配置local-root。
 *
 * <p>当 {@code meeting.storage.type=local} 或 {@code meeting.storage.type=minio} 时，
 * 必须配置 {@code meeting.storage.local-root}，否则启动失败。
 *
 * <p>设计目标：fail-fast，避免运行时才发现配置缺失。
 */
@Component
public class StorageConfigValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StorageConfigValidator.class);

    @Value("${meeting.storage.type:local}")
    private String storageType;

    @Value("${meeting.storage.local-root:}")
    private String localRoot;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if ("local".equalsIgnoreCase(storageType) || "minio".equalsIgnoreCase(storageType)) {
            if (localRoot == null || localRoot.isBlank()) {
                String message = String.format(
                    "Storage configuration error: meeting.storage.type=%s requires " +
                    "meeting.storage.local-root to be configured. " +
                    "Set STORAGE_LOCAL_ROOT environment variable or " +
                    "meeting.storage.local-root property in application.yml",
                    storageType
                );
                log.error("storage_config_validation_failed type={} localRoot=<empty>", storageType);
                throw new IllegalStateException(message);
            }
            log.info("storage_config_validated type={} localRoot={}", storageType, localRoot);
        } else if ("oss".equalsIgnoreCase(storageType)) {
            log.info("storage_config_validated type=oss (OSS gateway will validate credentials)");
        } else {
            log.warn("storage_config_unknown_type type={}, proceeding anyway", storageType);
        }
    }
}
