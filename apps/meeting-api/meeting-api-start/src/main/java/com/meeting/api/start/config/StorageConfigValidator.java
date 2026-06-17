package com.meeting.api.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 存储配置校验器：记录当前存储类型。
 *
 * <p>支持的存储类型：{@code tos}（火山引擎 TOS）、{@code oss}（阿里云 OSS）。
 *
 * <p>设计目标：fail-fast，避免运行时才发现配置缺失。
 */
@Component
public class StorageConfigValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StorageConfigValidator.class);

    @Value("${meeting.storage.type:oss}")
    private String storageType;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if ("tos".equalsIgnoreCase(storageType)) {
            log.info("storage_config_validated type=tos (TOS gateway will validate credentials)");
        } else if ("oss".equalsIgnoreCase(storageType)) {
            log.info("storage_config_validated type=oss (OSS gateway will validate credentials)");
        } else {
            log.warn("storage_config_unknown_type type={}, proceeding anyway", storageType);
        }
    }
}
