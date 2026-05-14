import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getLatestMeetingTask, getTranscript, updateSegment } from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import type { ProcessingTask, TranscriptData, TranscriptSegment } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";

const STALE_BANNER_TEXT = "下游纪要、待办、决策、风险与 RAG chunk 已标记为 STALE，重新生成后会读取最新转录";
const VERSION_CONFLICT_TEXT = "内容已被更新；已自动刷新到最新版本，请重新编辑";

export function TranscriptPage() {
  const { meetingId = "" } = useParams();
  const [transcript, setTranscript] = useState<TranscriptData | null>(null);
  const [task, setTask] = useState<ProcessingTask | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingText, setEditingText] = useState("");
  const [editingReason, setEditingReason] = useState("");
  const [savingId, setSavingId] = useState<string | null>(null);
  const [staleNoticeVisible, setStaleNoticeVisible] = useState(false);

  const sortedSegments = useMemo(() => {
    return [...(transcript?.segments ?? [])].sort((a, b) => a.startMs - b.startMs);
  }, [transcript]);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [latestTask, nextTranscript] = await Promise.allSettled([
        getLatestMeetingTask(meetingId),
        getTranscript(meetingId),
      ]);
      if (latestTask.status === "fulfilled") setTask(latestTask.value);
      if (nextTranscript.status === "fulfilled") {
        setTranscript(nextTranscript.value);
      } else {
        const apiError = nextTranscript.reason as ApiClientError;
        if (apiError.status !== 404) {
          setError(apiError.code ? getUserMessage(apiError.code) : "转录加载失败");
        }
      }
    } finally {
      setLoading(false);
    }
  }, [meetingId]);

  useEffect(() => {
    if (!meetingId) return;
    void loadAll();
  }, [meetingId, loadAll]);

  const taskFailed = task?.status === "FAILED" || task?.status === "ORPHANED";
  const taskProcessing = task && !["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"].includes(task.status);

  const startEdit = (segment: TranscriptSegment) => {
    setEditingId(segment.segmentId);
    setEditingText(segment.currentText);
    setEditingReason("");
    setError(null);
  };

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
    setSavingId(segment.segmentId);
    setError(null);
    try {
      await updateSegment(meetingId, segment.segmentId, editingText, transcript.transcriptVersion, editingReason || null);
      setStaleNoticeVisible(true);
      cancelEdit();
      await loadAll();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      if (apiError.code === "VERSION_CONFLICT") {
        await loadAll();
        setError(VERSION_CONFLICT_TEXT);
        cancelEdit();
      } else {
        setError(apiError.code ? getUserMessage(apiError.code) : "保存失败");
      }
    } finally {
      setSavingId(null);
    }
  };

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">转录</h1>
          <p className="muted">{meetingId}</p>
        </div>
        <div className="toolbar">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/audio`}>上传音频</Link>
          <Link className="button" to={`/meetings/${meetingId}/minutes`}>查看纪要</Link>
          {task ? <Link className="button" to={`/meetings/${meetingId}/tasks/${task.taskId}`}>任务进度</Link> : null}
        </div>
      </div>

      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}

      {staleNoticeVisible ? (
        <section className="card stack" role="status" aria-live="polite">
          <strong>已应用编辑</strong>
          <span className="muted">{STALE_BANNER_TEXT}</span>
          <div className="toolbar">
            <button type="button" className="button" onClick={() => setStaleNoticeVisible(false)}>知道了</button>
            <Link className="button primary" to={`/meetings/${meetingId}/minutes`}>查看纪要</Link>
          </div>
        </section>
      ) : null}

      {taskProcessing ? (
        <section className="card stack">
          <div className="toolbar">
            <strong>处理中</strong>
            <span className="badge">{task.status}</span>
            {task.currentStep ? <span className="muted">{task.currentStep}</span> : null}
          </div>
          <div className="progress-bar">
            <span style={{ width: `${taskProgress(task)}%` }} />
          </div>
        </section>
      ) : null}

      {taskFailed ? (
        <section className="card stack">
          <div className="toolbar">
            <strong>处理失败</strong>
            <span className="badge">{task.status}</span>
            {task.lastErrorCode ? <span className="error">{getUserMessage(task.lastErrorCode)}</span> : null}
          </div>
          <Link className="button primary" to={`/meetings/${meetingId}/tasks/${task.taskId}`}>查看并重试</Link>
        </section>
      ) : null}

      <section className="card stack">
        <div className="toolbar">
          <strong>片段</strong>
          {transcript ? <span className="badge">v{transcript.transcriptVersion}</span> : null}
          <span className="muted">{sortedSegments.length} segments</span>
        </div>
        {sortedSegments.length === 0 && !loading ? (
          <p className="muted">暂无转录内容</p>
        ) : (
          <div className="transcript-list">
            {sortedSegments.map((segment) => (
              <article className="transcript-row" key={segment.segmentId}>
                <div className="transcript-meta">
                  <strong>{segment.speakerDisplayName || segment.speakerLabel}</strong>
                  <span className="muted">{formatMs(segment.startMs)} - {formatMs(segment.endMs)}</span>
                  <span className="badge">{Math.round(segment.asrConfidence * 100)}%</span>
                  {segment.editedText && segment.editedText !== segment.originalText ? (
                    <span className="badge">已编辑</span>
                  ) : null}
                </div>
                {editingId === segment.segmentId ? (
                  <div className="stack">
                    <textarea
                      aria-label={`编辑片段 ${segment.segmentId}`}
                      value={editingText}
                      onChange={(e) => setEditingText(e.target.value)}
                      rows={3}
                    />
                    <input
                      aria-label="编辑原因（可选）"
                      placeholder="编辑原因（可选）"
                      value={editingReason}
                      onChange={(e) => setEditingReason(e.target.value)}
                    />
                    <div className="toolbar">
                      <button
                        type="button"
                        className="button primary"
                        disabled={savingId === segment.segmentId}
                        onClick={() => void saveEdit(segment)}
                      >
                        {savingId === segment.segmentId ? "保存中" : "保存"}
                      </button>
                      <button type="button" className="button" onClick={cancelEdit} disabled={savingId === segment.segmentId}>
                        取消
                      </button>
                      {segment.editedText ? (
                        <span className="muted">原文：{segment.originalText}</span>
                      ) : null}
                    </div>
                  </div>
                ) : (
                  <div className="stack">
                    <p>{segment.currentText}</p>
                    <div className="toolbar">
                      <button type="button" className="button" onClick={() => startEdit(segment)}>编辑</button>
                    </div>
                  </div>
                )}
              </article>
            ))}
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

function formatMs(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}
