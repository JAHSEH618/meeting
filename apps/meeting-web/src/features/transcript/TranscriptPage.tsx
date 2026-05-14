import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getLatestMeetingTask, getTranscript } from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import type { ProcessingTask, TranscriptData } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";

export function TranscriptPage() {
  const { meetingId = "" } = useParams();
  const [transcript, setTranscript] = useState<TranscriptData | null>(null);
  const [task, setTask] = useState<ProcessingTask | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const sortedSegments = useMemo(() => {
    return [...(transcript?.segments ?? [])].sort((a, b) => a.startMs - b.startMs);
  }, [transcript]);

  useEffect(() => {
    if (!meetingId) return;
    let cancelled = false;
    async function load() {
      setLoading(true);
      setError(null);
      try {
        const [latestTask, nextTranscript] = await Promise.allSettled([
          getLatestMeetingTask(meetingId),
          getTranscript(meetingId),
        ]);
        if (cancelled) return;
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
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [meetingId]);

  const taskFailed = task?.status === "FAILED" || task?.status === "ORPHANED";
  const taskProcessing = task && !["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"].includes(task.status);

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
          {task ? <Link className="button" to={`/meetings/${meetingId}/tasks/${task.taskId}`}>任务进度</Link> : null}
        </div>
      </div>

      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}

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
                </div>
                <p>{segment.currentText}</p>
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
