import { useMemo } from "react";
import { Link, useParams } from "react-router-dom";
import { useTaskEventsStream } from "@shared/queries/useTaskEventsStream";
import { useRetryTask, useCancelTask } from "./queries";
import { PhaseStrip } from "@shared/components/PhaseStrip";
import { SourceLabel } from "@shared/components/SourceLabel";
import { getUserMessage } from "@shared/utils/error-mapper";

const STATUS_LABEL: Record<string, string> = {
  PENDING: "等待中",
  QUEUED: "已排队",
  RUNNING: "进行中",
  SUCCEEDED: "已完成",
  PARTIAL_SUCCEEDED: "部分完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  ORPHANED: "已回收",
  CANCEL_PENDING: "取消中",
};

const STATUS_DOT: Record<string, string> = {
  PENDING: "dot",
  QUEUED: "dot dot--info",
  RUNNING: "dot dot--info",
  SUCCEEDED: "dot dot--success",
  PARTIAL_SUCCEEDED: "dot dot--warn",
  FAILED: "dot dot--danger",
  CANCELLED: "dot",
  ORPHANED: "dot dot--danger",
  CANCEL_PENDING: "dot dot--warn",
};

const STEP_STATUS_PILL: Record<string, string> = {
  PENDING: "pill--neutral",
  QUEUED: "pill--neutral",
  RUNNING: "pill--info",
  SUCCEEDED: "pill--success",
  FAILED: "pill--danger",
  SKIPPED: "pill--neutral",
};

export function TaskProgressPage() {
  const { meetingId = "", taskId = "" } = useParams();
  const { snapshot, connectionMode } = useTaskEventsStream(taskId);
  const retry = useRetryTask();
  const cancel = useCancelTask();

  const totalProgress = useMemo(() => {
    if (snapshot.steps.length === 0) return 0;
    return Math.round(snapshot.steps.reduce((sum, s) => sum + s.progress, 0) / snapshot.steps.length);
  }, [snapshot.steps]);

  const isTerminal =
    snapshot.status &&
    ["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"].includes(snapshot.status as string);

  return (
    <div className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">PROCESSING</span>
          <h1 className="page-hero__title">任务进度</h1>
          <p className="page-hero__subtitle"><span translate="no">{taskId}</span></p>
        </div>
        <div className="page-hero__actions">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <button
            type="button"
            className="button"
            disabled={!snapshot.retryable || retry.isPending}
            onClick={() => retry.mutate(taskId)}
          >
            {retry.isPending ? "重试中…" : "重试"}
          </button>
          <button
            type="button"
            className="button"
            disabled={!!isTerminal || cancel.isPending}
            onClick={() => cancel.mutate(taskId)}
          >
            {cancel.isPending ? "取消中…" : "取消"}
          </button>
          <span className="pill" aria-label="连接模式">
            <span className={
              connectionMode === "SSE"
                ? "dot dot--success"
                : connectionMode === "POLLING"
                  ? "dot dot--warn"
                  : "dot"
            } />
            {connectionMode === "SSE" ? "SSE" : connectionMode === "POLLING" ? "轮询" : "已结束"}
          </span>
        </div>
      </header>

      <section className="stats-grid" aria-live="polite">
        <div className="stat-card">
          <div className="stat-card__label">状态</div>
          <div className="stat-card__value" style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span className={STATUS_DOT[snapshot.status as string] ?? "dot"} />
            <span>{snapshot.status}</span>
          </div>
          <div className="page-subtitle">{STATUS_LABEL[snapshot.status as string] ?? ""}</div>
        </div>
        <div className="stat-card">
          <div className="stat-card__label">阶段</div>
          <div className="stat-card__value" style={{ fontSize: 16 }}>{snapshot.phase ?? "—"}</div>
          <PhaseStrip phase={snapshot.phase} />
        </div>
        <div className="stat-card">
          <div className="stat-card__label">尝试</div>
          <div className="stat-card__value">{snapshot.attemptNo}</div>
        </div>
        <div className="stat-card">
          <div className="stat-card__label">总体进度</div>
          <div className="stat-card__value">{totalProgress}%</div>
          <div className="progress">
            <span style={{ display: "block", height: "100%", width: `${totalProgress}%`, background: "var(--accent)" }} />
          </div>
        </div>
      </section>

      {snapshot.lastErrorCode ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">最近错误</strong>
          <span className="banner__body">
            {getUserMessage(snapshot.lastErrorCode)} · <code translate="no">{snapshot.lastErrorCode}</code>
          </span>
        </div>
      ) : null}

      <section className="glass-panel glass-panel--table stack">
        <div className="toolbar">
          <strong>步骤</strong>
          {snapshot.currentStep ? (
            <span className="page-subtitle">当前 step: {snapshot.currentStep}</span>
          ) : null}
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>步骤</th>
              <th>状态</th>
              <th>进度</th>
              <th>来源</th>
              <th className="num">尝试</th>
            </tr>
          </thead>
          <tbody>
            {snapshot.steps.map((step) => (
              <tr key={step.stepName}>
                <td>
                  <strong>{step.stepName}</strong>
                  {step.stepName === "AUDIO_UPLOAD" ? (
                    <div className="page-subtitle">已完成于任务创建时</div>
                  ) : null}
                </td>
                <td>
                  <span className={`pill ${STEP_STATUS_PILL[step.status] ?? "pill--neutral"}`}>
                    {step.status}
                  </span>
                </td>
                <td style={{ minWidth: 160 }}>
                  <div className="progress">
                    <span style={{ display: "block", height: "100%", width: `${step.progress}%`, background: "var(--accent)" }} />
                  </div>
                  <span className="page-subtitle">{step.progress}%</span>
                </td>
                <td><SourceLabel source={step.source ?? null} /></td>
                <td className="num">{step.attemptNo ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
