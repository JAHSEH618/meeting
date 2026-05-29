package com.meeting.api.start.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling independently of any individual scheduled job.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
