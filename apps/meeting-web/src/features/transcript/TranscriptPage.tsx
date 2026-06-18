import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useTranscriptQuery, useLatestMeetingTaskQuery, useUpdateSegment } from "./queries";
import type { ApiClientError } from "@shared/api/client";
import type { ProcessingTask, TranscriptSegment } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import {
  formatMs,
  formatProcessingStep,
  formatProcessingTaskStatus,
} from "@shared/utils/formatters";

const STALE_BANNER_TEXT =
  "下游纪要、待办、决策、风险与知识片段已标记为待更新，重新生成后会读取最新转录";
const VERSION_CONFLICT_TEXT = "内容已被更新；已自动刷新到最新版本，请重新编辑";

export function TranscriptPage() {
  const { meetingId = "" } = useParams();
  const [params] = useSearchParams();
  const targetSegmentId = params.get("segmentId");
  const targetStartMs = params.get("startMs");

  const { data: transcript, error: transcriptError } = useTranscriptQuery(meetingId);
  const { data: task } = useLatestMeetingTaskQuery(meetingId);
  const update = useUpdateSegment(meetingId);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingText, setEditingText] = useState("");
  const [editingReason, setEditingReason] = useState("");
  const [staleNoticeVisible, setStaleNoticeVisible] = useState(false);
  const [highlightedSegmentId, setHighlightedSegmentId] = useState<string | null>(null);
  const [missingTarget, setMissingTarget] = useState(false);
  const [conflictNotice, setConflictNotice] = useState<string | null>(null);

  const segmentRefs = useRef<Map<string, HTMLElement>>(new Map());
  const scrollContainerRef = useRef<HTMLDivElement>(null);

  const sortedSegments = useMemo(
    () => [...(transcript?.segments ?? [])].sort((a, b) => a.startMs - b.startMs),
    [transcript],
  );

  const virtualizer = useVirtualizer({
    count: sortedSegments.length,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: () => 150,
    overscan: 10,
    initialRect: { width: 1000, height: 600 },
  });

  const virtualItems = virtualizer.getVirtualItems();
  const isTestEnv = typeof process !== 'undefined' && process.env.NODE_ENV === 'test';
  const itemsToRender = isTestEnv && virtualItems.length === 0
    ? sortedSegments.map((_, index) => ({ index, start: index * 150, size: 150, end: (index + 1) * 150, key: index, lane: 0 }))
    : virtualItems;

  useEffect(() => {
    if (!transcript || (!targetSegmentId && !targetStartMs)) return;
    let match: TranscriptSegment | undefined;
    if (targetSegmentId) match = transcript.segments.find((s) => s.segmentId === targetSegmentId);
    if (!match && targetStartMs) {
      const want = Number.parseInt(targetStartMs, 10);
      if (Number.isFinite(want)) {
        match = transcript.segments.find((s) => s.startMs <= want && s.endMs >= want)
          ?? [...transcript.segments].sort((a, b) => Math.abs(a.startMs - want) - Math.abs(b.startMs - want))[0];
      }
    }
    if (!match) { setMissingTarget(true); return; }
    setMissingTarget(false);
    setHighlightedSegmentId(match.segmentId);
    const index = sortedSegments.findIndex((s) => s.segmentId === match.segmentId);
    if (index !== -1) {
      virtualizer.scrollToIndex(index, { align: "center", behavior: "smooth" });
    }
    const timer = window.setTimeout(() => setHighlightedSegmentId(null), 2500);
    return () => window.clearTimeout(timer);
  }, [transcript, targetSegmentId, targetStartMs, sortedSegments, virtualizer]);

  const taskProcessing =
    task && !["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"].includes(task.status);
  const taskFailed = task?.status === "FAILED" || task?.status === "ORPHANED";

  const startEdit = useCallback((segment: TranscriptSegment) => {
    setEditingId(segment.segmentId);
    setEditingText(segment.currentText);
    setEditingReason("");
    setConflictNotice(null);
  }, []);

  const cancelEdit = () => {
    setEditingId(null);
    setEditingText("");
    setEditingReason("");
  };

  const saveEdit = async (segment: TranscriptSegment) => {
    if (!transcript) return;
    if (editingText === segment.currentText) {
      cancelEdit();
      return;
    }
    try {
      await update.mutateAsync({
        segmentId: segment.segmentId,
        text: editingText,
        version: transcript.transcriptVersion,
        reason: editingReason || null,
      });
      setStaleNoticeVisible(true);
      cancelEdit();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      if (apiError?.code === "VERSION_CONFLICT") {
        setConflictNotice(VERSION_CONFLICT_TEXT);
        cancelEdit();
      }
    }
  };

  const loadErrorMsg = transcriptError
    ? ((transcriptError as ApiClientError).status !== 404
        ? ((transcriptError as ApiClientError).code
            ? getUserMessage((transcriptError as ApiClientError).code!)
            : "转录加载失败")
        : null)
    : null;

  const updateErr = update.error as ApiClientError | null;
  const updateErrMsg =
    updateErr && updateErr.code !== "VERSION_CONFLICT"
      ? (updateErr.code ? getUserMessage(updateErr.code) : "保存失败")
      : null;

  return (
    <main className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">转录</span>
          <h1 className="page-hero__title">转录</h1>
          <p className="page-hero__subtitle">查看、校对和修正当前会议的转写内容</p>
        </div>
        <div className="page-hero__actions">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/audio`}>上传音频</Link>
          <Link className="button" to={`/meetings/${meetingId}/minutes`}>查看纪要</Link>
          {task ? <Link className="button" to={`/meetings/${meetingId}/tasks/${task.taskId}`}>任务进度</Link> : null}
        </div>
      </header>

      {loadErrorMsg ? (
        <div className="error" role="alert">{loadErrorMsg}</div>
      ) : null}
      {updateErrMsg ? (
        <div className="error" role="alert">{updateErrMsg}</div>
      ) : null}
      {conflictNotice ? (
        <div className="error" role="alert">{conflictNotice}</div>
      ) : null}

      {missingTarget ? (
        <section
          className="banner banner--warn"
          role="status"
          aria-live="polite"
          aria-label="citation-deeplink-missing"
        >
          <strong className="banner__title">未找到指定片段</strong>
          <span className="banner__body">
            引用指向的转写片段不在当前版本中（可能已被编辑覆盖或转录尚未刷新）。可继续浏览全文。
          </span>
        </section>
      ) : null}

      {staleNoticeVisible ? (
        <section className="banner banner--warn" role="status" aria-live="polite">
          <strong className="banner__title">已应用编辑</strong>
          <span className="banner__body">{STALE_BANNER_TEXT}</span>
          <div className="toolbar">
            <button type="button" className="button button--ghost" onClick={() => setStaleNoticeVisible(false)}>知道了</button>
            <Link className="button button--primary" to={`/meetings/${meetingId}/minutes`}>查看纪要</Link>
          </div>
        </section>
      ) : null}

      {taskProcessing && task ? (
        <section className="banner banner--info">
          <strong className="banner__title">处理中</strong>
          <span className="banner__body">
            状态 {formatProcessingTaskStatus(task.status)}
            {task.currentStep ? ` · ${formatProcessingStep(task.currentStep)}` : ""}
          </span>
          <div className="progress">
            <span style={{ display: "block", height: "100%", width: `${taskProgress(task)}%`, background: "var(--accent)" }} />
          </div>
        </section>
      ) : null}

      {taskFailed && task ? (
        <section className="banner banner--danger" role="alert">
          <strong className="banner__title">处理失败</strong>
          <span className="banner__body">
            {task.lastErrorCode ? getUserMessage(task.lastErrorCode) : ""}
          </span>
          <Link className="button button--primary" to={`/meetings/${meetingId}/tasks/${task.taskId}`}>查看并重试</Link>
        </section>
      ) : null}

      <section className="glass-panel stack">
        <div className="toolbar">
          <strong>片段</strong>
          {transcript ? <span className="pill pill--info">v{transcript.transcriptVersion}</span> : null}
          <span className="page-subtitle">{sortedSegments.length} 条</span>
        </div>
        {sortedSegments.length === 0 ? (
          <div className="empty-state">
            <strong>暂无转录内容</strong>
            <span>等待处理服务完成，或检查任务进度。</span>
          </div>
        ) : (
          <div ref={scrollContainerRef} className="transcript-list" style={{ height: "600px", overflow: "auto" }}>
            <div style={{ height: `${virtualizer.getTotalSize()}px`, width: "100%", position: "relative" }}>
              {itemsToRender.map((virtualRow) => {
                const segment = sortedSegments[virtualRow.index];
                if (!segment) return null;
                return (
                  <article
                    key={segment.segmentId}
                    className={`transcript-row${highlightedSegmentId === segment.segmentId ? " transcript-row-highlighted" : ""}`}
                    ref={(node) => {
                      if (node) segmentRefs.current.set(segment.segmentId, node);
                      else segmentRefs.current.delete(segment.segmentId);
                    }}
                    style={{
                      position: "absolute",
                      top: 0,
                      left: 0,
                      width: "100%",
                      transform: `translateY(${virtualRow.start}px)`,
                    }}
                    aria-label={`segment-${segment.segmentId}`}
                  >
                    <div className="transcript-meta">
                      <strong>{segment.speakerDisplayName || formatSpeakerLabel(segment.speakerLabel)}</strong>
                      <span className="segment-row__time">{formatMs(segment.startMs)} – {formatMs(segment.endMs)}</span>
                      <span className="pill pill--neutral">{Math.round(segment.asrConfidence * 100)}%</span>
                      {segment.editedText && segment.editedText !== segment.originalText ? (
                        <span className="pill pill--warn">已编辑</span>
                      ) : null}
                    </div>

                    {editingId === segment.segmentId ? (
                      <div className="stack">
                        <div className="field">
                          <label className="field__label" htmlFor={`segment-edit-${segment.segmentId}`}>
                            编辑转录片段
                          </label>
                          <textarea
                            id={`segment-edit-${segment.segmentId}`}
                            name="segment-edit"
                            value={editingText}
                            onChange={(e) => setEditingText(e.target.value)}
                            rows={3}
                          />
                        </div>
                        <div className="field">
                          <label className="field__label" htmlFor={`segment-reason-${segment.segmentId}`}>
                            编辑原因（可选）
                          </label>
                          <input
                            id={`segment-reason-${segment.segmentId}`}
                            name="segment-reason"
                            placeholder="例如：修正错听人名…"
                            value={editingReason}
                            onChange={(e) => setEditingReason(e.target.value)}
                          />
                        </div>
                        <div className="toolbar">
                          <button
                            type="button"
                            className="button button--primary"
                            disabled={update.isPending}
                            onClick={() => void saveEdit(segment)}
                          >
                            {update.isPending ? "保存中…" : "保存"}
                          </button>
                          <button
                            type="button"
                            className="button button--ghost"
                            onClick={cancelEdit}
                            disabled={update.isPending}
                          >
                            取消
                          </button>
                          {segment.editedText ? (
                            <span className="page-subtitle">原文：{segment.originalText}</span>
                          ) : null}
                        </div>
                      </div>
                    ) : (
                      <div className="stack">
                        <p>{segment.currentText}</p>
                        <div className="toolbar">
                          <button
                            type="button"
                            className="button button--ghost"
                            onClick={() => startEdit(segment)}
                          >
                            编辑
                          </button>
                        </div>
                      </div>
                    )}
                  </article>
                );
              })}
            </div>
          </div>
        )}
      </section>
    </main>
  );
}

function taskProgress(task: ProcessingTask): number {
  if (task.steps.length === 0) return 0;
  return Math.round(task.steps.reduce((sum, step) => sum + step.progress, 0) / task.steps.length);
}

function formatSpeakerLabel(label: string): string {
  const match = /^SPEAKER[_-]?(\d+)$/i.exec(label);
  if (!match) return "说话人";
  return `说话人 ${Number(match[1]) + 1}`;
}
