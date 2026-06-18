export const dateFormatter = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "medium",
  timeStyle: "short",
});

export const dateShortFormatter = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "short",
});

export const numberFormatter = new Intl.NumberFormat("zh-CN");

export const percentFormatter = new Intl.NumberFormat("zh-CN", {
  style: "percent",
  maximumFractionDigits: 1,
});

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return dateFormatter.format(date);
}

export function formatShortDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return dateShortFormatter.format(date);
}

export function formatMs(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
    : `${m}:${String(s).padStart(2, "0")}`;
}

export function formatPercent(ratio: number): string {
  return percentFormatter.format(ratio);
}

export function formatNumber(n: number): string {
  return numberFormatter.format(n);
}

function labelFrom(
  labels: Record<string, string>,
  value: string | null | undefined,
  fallback = "未知",
): string {
  if (!value) return fallback;
  return labels[value] ?? fallback;
}

export const MEETING_STATUS_LABELS: Record<string, string> = {
  CREATED: "已创建",
  PROCESSING: "处理中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  DELETED: "已删除",
};

export function formatMeetingStatus(status: string | null | undefined): string {
  return labelFrom(MEETING_STATUS_LABELS, status, "未知状态");
}

export const PROCESSING_TASK_STATUS_LABELS: Record<string, string> = {
  PENDING: "等待中",
  QUEUED: "排队中",
  RUNNING: "处理中",
  ORPHANED: "已回收",
  PARTIAL_SUCCEEDED: "部分完成",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCEL_PENDING: "取消中",
  CANCELLED: "已取消",
};

export function formatProcessingTaskStatus(status: string | null | undefined): string {
  return labelFrom(PROCESSING_TASK_STATUS_LABELS, status, "未知状态");
}

export const PROCESSING_STEP_STATUS_LABELS: Record<string, string> = {
  PENDING: "等待中",
  QUEUED: "排队中",
  RUNNING: "处理中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  SKIPPED: "已跳过",
  CANCELLED: "已取消",
};

export function formatProcessingStepStatus(status: string | null | undefined): string {
  return labelFrom(PROCESSING_STEP_STATUS_LABELS, status, "未知状态");
}

export const PROCESSING_PHASE_LABELS: Record<string, string> = {
  WORKER_DAG_RUNNING: "音频处理",
  WORKER_DAG_DONE: "音频处理完成",
  JAVA_LLM_RUNNING: "内容生成",
  TERMINAL: "处理结束",
};

export function formatProcessingPhase(phase: string | null | undefined): string {
  return labelFrom(PROCESSING_PHASE_LABELS, phase, "未开始");
}

export const PROCESSING_STEP_LABELS: Record<string, string> = {
  AUDIO_UPLOAD: "音频上传",
  AUDIO_PREPROCESS: "音频预处理",
  ASR: "语音识别",
  ALIGNMENT: "时间轴对齐",
  DIARIZATION: "说话人分离",
  SPEAKER_EMBEDDING: "声纹提取",
  SPEAKER_MATCHING: "说话人匹配",
  TRANSCRIPT_MERGE: "转录合并",
  SUMMARY: "纪要生成",
  EXTRACTION: "事项提取",
  RAG_INDEXING: "知识索引",
  EXPORT: "导出渲染",
};

export function formatProcessingStep(step: string | null | undefined): string {
  return labelFrom(PROCESSING_STEP_LABELS, step, "未知步骤");
}

export const DOCUMENT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "可用",
  DELETED: "已删除",
  PENDING: "等待中",
  INDEXING: "索引中",
  STALE: "待更新",
  FAILED: "失败",
};

export function formatDocumentStatus(status: string | null | undefined): string {
  return labelFrom(DOCUMENT_STATUS_LABELS, status, "未知状态");
}

export const TEXT_EXTRACTION_STATUS_LABELS: Record<string, string> = {
  PENDING: "等待解析",
  EXTRACTED: "已解析",
  FAILED: "解析失败",
  OCR_UNSUPPORTED: "暂不支持 OCR",
  TYPE_UNSUPPORTED: "类型不支持",
};

export function formatTextExtractionStatus(status: string | null | undefined): string {
  return labelFrom(TEXT_EXTRACTION_STATUS_LABELS, status, "未知解析状态");
}

export const CONSENT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "已授权",
  REVOKED: "已撤销",
  EXPIRED: "已过期",
  UNKNOWN: "未知授权",
};

export function formatConsentStatus(status: string | null | undefined): string {
  return labelFrom(CONSENT_STATUS_LABELS, status, "未知授权");
}

export const SPEAKER_CONFIRMATION_STATUS_LABELS: Record<string, string> = {
  PENDING: "待确认",
  CONFIRMED: "已确认",
  REJECTED: "已拒绝",
  EXPIRED: "已过期",
};

export function formatSpeakerConfirmationStatus(status: string | null | undefined): string {
  return labelFrom(SPEAKER_CONFIRMATION_STATUS_LABELS, status, "未知状态");
}

export const ENROLLMENT_STATUS_LABELS: Record<string, string> = {
  PENDING: "注册中",
  RUNNING: "注册中",
  SUCCEEDED: "注册成功",
  FAILED: "注册失败",
};

export function formatEnrollmentStatus(status: string | null | undefined): string {
  return labelFrom(ENROLLMENT_STATUS_LABELS, status, "未知状态");
}

export const UPLOAD_STATUS_LABELS: Record<string, string> = {
  idle: "未开始",
  preparing: "准备中",
  uploading: "上传中",
  completing: "收尾中",
  completed: "已完成",
  failed: "失败",
  aborted: "已取消",
  expired: "已过期",
  PENDING: "等待中",
  UPLOADING: "上传中",
  COMPLETED: "已完成",
  EXPIRED: "已过期",
};

export function formatUploadStatus(status: string | null | undefined): string {
  return labelFrom(UPLOAD_STATUS_LABELS, status, "未知状态");
}

export const ITEM_STATUS_LABELS: Record<string, string> = {
  OPEN: "开放",
  PROPOSED: "待确认",
  CLOSED: "已关闭",
  RESOLVED: "已解决",
  DRAFT: "草稿",
  ACCEPTED: "已确认",
  REJECTED: "已拒绝",
  NEEDS_REVIEW: "待复核",
};

export function formatItemStatus(status: string | null | undefined): string {
  return labelFrom(ITEM_STATUS_LABELS, status, "未知状态");
}

export const STALE_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "最新",
  CURRENT: "最新",
  STALE: "待更新",
  SUPERSEDED: "已被新版本替代",
};

export function formatStaleStatus(status: string | null | undefined): string {
  return labelFrom(STALE_STATUS_LABELS, status, "未知版本");
}

export const PRIORITY_LABELS: Record<string, string> = {
  LOW: "低",
  MEDIUM: "中",
  HIGH: "高",
  CRITICAL: "紧急",
};

export function formatPriority(value: string | null | undefined): string {
  return labelFrom(PRIORITY_LABELS, value, "未设置");
}

export function formatLanguage(value: string | null | undefined): string {
  return labelFrom(
    {
      "zh-CN": "中文",
      zh: "中文",
      "en-US": "英文",
      en: "英文",
    },
    value,
    "未设置",
  );
}
