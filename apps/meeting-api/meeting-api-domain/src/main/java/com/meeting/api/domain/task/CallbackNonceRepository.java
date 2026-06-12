package com.meeting.api.domain.task;

import java.time.OffsetDateTime;

/**
 * Callback nonce 去重 Repository
 * 用于防止重放攻击，5分钟 TTL 窗口
 */
public interface CallbackNonceRepository {

    /**
     * 检查 nonce 是否已存在
     *
     * @param tenantId 租户ID
     * @param nonce nonce 值
     * @return true 如果已存在（重放攻击）
     */
    boolean exists(String tenantId, String nonce);

    /**
     * 记录 nonce，默认 5 分钟过期
     *
     * @param tenantId 租户ID
     * @param nonce nonce 值
     * @param workerId worker ID
     * @param taskId 任务ID (可选)
     * @param stepName 步骤名称 (可选)
     * @return 记录是否成功（false 表示已存在）
     */
    boolean record(String tenantId, String nonce, String workerId, String taskId, String stepName);

    /**
     * 清理过期的 nonce
     *
     * @param before 清理此时间之前过期的记录
     * @return 清理的记录数
     */
    int cleanupExpired(OffsetDateTime before);
}
