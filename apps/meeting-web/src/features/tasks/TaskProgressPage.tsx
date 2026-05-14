import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { cancelTask, getTask, retryTask, subscribeTaskEvents } from "@shared/api/client";
import type { ApiClientError, TaskEventSubscription } from "@shared/api/client";
import type { ProcessingTask, ProcessingTaskStatus } from "@shared/api/types";
import { createInitialSnapshot, sseReducer, type TaskSnapshot } from "@shared/utils/sse-reducer";
import { getUserMessage } from "@shared/utils/error-mapper";

const TERMINAL_STATUSES: ProcessingTaskStatus[] = [
  "SUCCEEDED",
  "PARTIAL_SUCCEEDED",
  "FAILED",
  "CANCELLED",
];

const POLL_INTERVAL_MS = 3000;

function snapshotFromTask(task: ProcessingTask): TaskSnapshot {
  return {
    ...createInitialSnapshot(),
    taskId: task.taskId,
    meetingId: task.meetingId ?? "",
    status: task.status,
    phase: task.phase,
    attemptNo: task.attemptNo,
    currentStep: task.currentStep ?? null,
    lastErrorCode: task.lastErrorCode ?? null,
    retryable: task.retryable ?? false,
    steps: task.steps,
    completedSteps: task.steps.filter((step) => step.status === "SUCCEEDED").map((step) => step.stepName),
    leaseExpiresAt: "leaseExpiresAt" in task ? String(task.leaseExpiresAt ?? "") : undefined,
  };
}

function isTerminal(status: ProcessingTaskStatus | string | null): boolean {
  if (status === null) return false;
  return TERMINAL_STATUSES.some((terminal) => terminal === status);
}

export function TaskProgressPage() {
  const { meetingId = "", taskId = "" } = useParams();
  const [snapshot, setSnapshot] = useState<TaskSnapshot>(createInitialSnapshot());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [connectionMode, setConnectionMode] = useState<"SSE" | "POLLING">("SSE");
  const lastEventId = useRef<string | null>(null);
  const subscription = useRef<TaskEventSubscription | null>(null);

  const totalProgress = useMemo(() => {
    if (snapshot.steps.length === 0) return 0;
    return Math.round(snapshot.steps.reduce((sum, step) => sum + step.progress, 0) / snapshot.steps.length);
  }, [snapshot.steps]);

  useEffect(() => {
    if (!taskId) return;
    let cancelled = false;

    async function loadSnapshot() {
      try {
        const task = await getTask(taskId);
        if (!cancelled) setSnapshot(snapshotFromTask(task));
      } catch (cause) {
        const apiError = cause as ApiClientError;
        if (!cancelled) setError(apiError.code ? getUserMessage(apiError.code) : "任务加载失败");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void loadSnapshot();
    return () => {
      cancelled = true;
    };
  }, [taskId]);

  useEffect(() => {
    if (!taskId || loading) return;
    if (isTerminal(snapshot.status)) return;
    setConnectionMode("SSE");
    subscription.current = subscribeTaskEvents(taskId, {
      lastEventId: lastEventId.current,
      onEvent: (event) => {
        lastEventId.current = event.eventId;
        setSnapshot((current) => sseReducer(current.taskId ? current : createInitialSnapshot(), event));
      },
      onFallback: () => setConnectionMode("POLLING"),
    });
    return () => {
      subscription.current?.close();
      subscription.current = null;
    };
  }, [taskId, loading, snapshot.status]);

  useEffect(() => {
    if (connectionMode !== "POLLING" || !taskId) return;
    if (isTerminal(snapshot.status)) return;
    const timer = window.setInterval(() => {
      void getTask(taskId)
        .then((task) => setSnapshot(snapshotFromTask(task)))
        .catch(() => {
          // ignore transient polling failures; next tick retries
        });
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [connectionMode, taskId, snapshot.status]);

  async function retry() {
    try {
      const task = await retryTask(taskId);
      setSnapshot(snapshotFromTask(task));
      setError(null);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "重试失败");
    }
  }

  async function cancel() {
    try {
      const task = await cancelTask(taskId);
      setSnapshot(snapshotFromTask(task));
      setError(null);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "取消失败");
    }
  }

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">任务进度</h1>
          <p className="muted">{taskId}</p>
        </div>
        <div className="toolbar">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <button type="button" onClick={retry} disabled={!snapshot.retryable}>重试</button>
          <button type="button" onClick={cancel} disabled={snapshot.status === "CANCELLED" || snapshot.status === "SUCCEEDED"}>取消</button>
        </div>
      </div>

      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}

      <section className="grid" aria-live="polite">
        <div className="card">
          <div className="muted">状态</div>
          <h2>{snapshot.status}</h2>
        </div>
        <div className="card">
          <div className="muted">阶段</div>
          <h2>{snapshot.phase ?? "-"}</h2>
        </div>
        <div className="card">
          <div className="muted">连接</div>
          <h2>{isTerminal(snapshot.status) ? "已结束" : connectionMode === "POLLING" ? "轮询" : "SSE"}</h2>
        </div>
        <div className="card">
          <div className="muted">尝试次数</div>
          <h2>{snapshot.attemptNo}</h2>
        </div>
      </section>

      <section className="card stack">
        <div className="toolbar">
          <strong>总体进度</strong>
          <span className="badge">{totalProgress}%</span>
          {snapshot.currentStep ? <span className="muted">当前 step: {snapshot.currentStep}</span> : null}
          {snapshot.lastErrorCode ? <span className="error">{getUserMessage(snapshot.lastErrorCode)}</span> : null}
        </div>
        <div className="progress-bar"><span style={{ width: `${totalProgress}%` }} /></div>
        <div className="step-list">
          {snapshot.steps.map((step) => (
            <div className="step-row" key={step.stepName}>
              <strong>{step.stepName}</strong>
              <span className="badge">{step.status}</span>
              <div className="progress-bar" aria-label={`${step.stepName} progress`}>
                <span style={{ width: `${step.progress}%` }} />
              </div>
              <span>{step.progress}%</span>
              <span className="muted">{step.source}</span>
              <span className="muted">attempt {step.attemptNo ?? "-"}</span>
              <span className="muted">{step.retryable ? "retryable" : ""}</span>
            </div>
          ))}
        </div>
      </section>
    </main>
  );
}
