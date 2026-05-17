const ERROR_MESSAGES: Record<string, string> = {
  AUTH_REQUIRED: "请先登录",
  PERMISSION_DENIED: "没有执行该操作的权限",
  TENANT_CONTEXT_MISSING: "租户上下文缺失",
  VALIDATION_FAILED: "请求参数不符合要求",
  VERSION_CONFLICT: "内容已被更新，请刷新后重试",
  IDEMPOTENCY_CONFLICT: "幂等键已被不同请求使用",
  TASK_NOT_FOUND: "任务不存在",
  TASK_ATTEMPT_CONFLICT: "任务尝试次数已变化",
  TASK_LEASE_CONFLICT: "任务租约已变化",
  INVALID_TASK_MESSAGE: "任务消息不符合处理要求",
  TOS_OBJECT_NOT_FOUND: "存储对象不存在",
  TOS_READ_FAILED: "存储读取失败",
  TOS_WRITE_FAILED: "存储写入失败",
  UPLOAD_SESSION_EXPIRED: "上传会话已过期",
  UPLOAD_ALREADY_ABORTED: "上传会话已取消",
  UPLOAD_ALREADY_COMPLETED: "上传已完成",
  UPLOAD_PART_HASH_MISMATCH: "分片校验失败",
  UPLOAD_FILE_HASH_MISMATCH: "文件校验失败",
  UPLOAD_TOO_MANY_PARTS: "上传分片数量超过上限",
  UPLOAD_INCOMPLETE_PARTS: "上传分片不完整",
  AUDIO_UNSUPPORTED_FORMAT: "音频格式不支持",
  AUDIO_TOO_LONG: "音频超过 4 小时上限",
  AUDIO_CORRUPTED: "音频文件损坏或无法读取",
  AUDIO_QUALITY_LOW: "音频质量过低",
  ASR_RUNTIME_ERROR: "ASR 推理异常",
  ASR_MODEL_TIMEOUT: "ASR 模型推理超时",
  ASR_GPU_OOM: "ASR GPU 显存不足",
  DIARIZATION_FAILED: "说话人分离失败",
  ALIGNMENT_FAILED: "时间戳对齐失败",
  SPEAKER_EMBEDDING_FAILED: "声纹特征提取失败",
  SPEAKER_MATCH_FAILED: "声纹匹配失败",
  TRANSCRIPT_MERGE_FAILED: "转录合并失败",
  CALLBACK_AUTH_FAILED: "Worker 回调鉴权失败",
  LLM_PROVIDER_TIMEOUT: "LLM 服务超时",
  LLM_SCHEMA_INVALID: "LLM 输出格式不符合要求",
  LLM_EVIDENCE_INVALID: "LLM 输出依据校验失败",
  LLM_RATE_LIMIT: "LLM 服务限流",
  SECURITY_LEVEL_BLOCKED: "一期不支持该安全等级的自动 LLM 处理",
  LLM_DATA_BOUNDARY_BLOCKED: "数据边界策略阻断",
  RAG_INDEX_FAILED: "RAG 入库失败",
  VECTOR_SEARCH_FAILED: "向量检索失败",
  RERANK_UNAVAILABLE: "Rerank 服务不可用，已尝试降级",
  RERANK_CONTRACT_ERROR: "Rerank 内部契约或签名配置错误",
  EXPORT_FAILED: "导出失败",
  EXPORT_RENDER_FAILED: "导出渲染失败",
  EXPORT_LINK_REVOKED: "导出链接已被撤销",
  EXPORT_CONTENT_STALE: "导出依赖的内容已变更，请先重新生成纪要后重试",
  EXPORT_FORMAT_UNSUPPORTED: "不支持的导出格式",
  EXPORT_ALREADY_FINISHED: "导出任务已结束，无法取消",
  OUTBOX_PUBLISH_FAILED: "事件发布失败",
  STALE_REBUILD_VERSION_MISMATCH: "重建完成时上游版本已变化",
  KMS_KEY_UNAVAILABLE: "声纹加密密钥不可用",
  LEGAL_HOLD_BLOCKED: "对象处于法定保全状态，不能删除",
  DEPENDENCY_UNAVAILABLE: "依赖服务暂不可用",
  INTERNAL_ERROR: "服务内部错误",
};

export function getUserMessage(code: string): string {
  return ERROR_MESSAGES[code] ?? code;
}

export function isAuthError(code: string): boolean {
  return code === "AUTH_REQUIRED" || code === "PERMISSION_DENIED";
}

export function isRetryable(code: string): boolean {
  const retryable = new Set([
    "WORKER_LEASE_EXPIRED", "TOS_READ_FAILED", "TOS_WRITE_FAILED",
    "CHANNEL_MAP_FAILED", "ASR_RUNTIME_ERROR", "ASR_MODEL_TIMEOUT",
    "ASR_GPU_OOM", "DIARIZATION_FAILED", "ALIGNMENT_FAILED",
    "SPEAKER_EMBEDDING_FAILED", "SPEAKER_MATCH_FAILED",
    "TRANSCRIPT_MERGE_FAILED", "WRITEBACK_FAILED",
    "LLM_PROVIDER_TIMEOUT", "LLM_SCHEMA_INVALID", "LLM_EVIDENCE_INVALID",
    "LLM_RATE_LIMIT", "RAG_INDEX_FAILED", "VECTOR_SEARCH_FAILED",
    "RERANK_UNAVAILABLE", "EXPORT_FAILED", "EXPORT_RENDER_FAILED",
    "OUTBOX_PUBLISH_FAILED", "KMS_KEY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE",
  ]);
  return retryable.has(code);
}
