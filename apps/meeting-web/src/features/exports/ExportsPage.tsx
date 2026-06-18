import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  cancelExport,
  createExport,
  getAuthToken,
  getMeeting,
  listMeetingExports,
  revokeExportLink,
  type ApiClientError,
  type CreateExportInput,
  type ExportFormat,
  type ExportJob,
} from "@shared/api/client";
import type { Meeting } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import { fetchSSE } from "@shared/utils/fetch-sse";

const FORMAT_LABELS: Record<ExportFormat, string> = {
  MARKDOWN: "Markdown",
  DOCX: "Word (DOCX)",
  PDF: "PDF",
};

const STATUS_LABELS: Record<ExportJob["status"], string> = {
  QUEUED: "排队中",
  RUNNING: "渲染中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  REVOKED: "已撤销",
};

const TERMINAL_STATUSES: ReadonlySet<ExportJob["status"]> = new Set([
  "SUCCEEDED",
  "FAILED",
  "CANCELLED",
  "REVOKED",
]);

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(
    date.getDate(),
  ).padStart(2, "0")} ${String(date.getHours()).padStart(2, "0")}:${String(
    date.getMinutes(),
  ).padStart(2, "0")}`;
}

export function ExportsPage() {
  const { meetingId = "" } = useParams();

  const [meeting, setMeeting] = useState<Meeting | null>(null);
  const [jobs, setJobs] = useState<ExportJob[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Create form state
  const [format, setFormat] = useState<ExportFormat>("PDF");
  const [watermark, setWatermark] = useState("");
  const [includeTranscript, setIncludeTranscript] = useState(true);
  const [includeMinutes, setIncludeMinutes] = useState(true);
  const [includeItems, setIncludeItems] = useState(true);
  const [includeSpeakers, setIncludeSpeakers] = useState(true);
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<ExportJob | null>(null);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const hasActiveJob = useMemo(
    () => jobs.some((j) => !TERMINAL_STATUSES.has(j.status)),
    [jobs],
  );

  const loadAll = useCallback(async () => {
    if (!meetingId) return;
    setError(null);
    setPending(true);
    try {
      const [meetingResp, jobsResp] = await Promise.all([
        getMeeting(meetingId),
        listMeetingExports(meetingId),
      ]);
      setMeeting(meetingResp);
      setJobs(jobsResp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载导出任务失败");
    } finally {
      setPending(false);
    }
  }, [meetingId]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  // Re-fetch every 3s while any job is in QUEUED/RUNNING so the user
  // sees progress without manual refresh; stops when all are terminal.
  useEffect(() => {
    if (!hasActiveJob) return;
    const handle = window.setInterval(() => void loadAll(), 3000);
    return () => window.clearInterval(handle);
  }, [hasActiveJob, loadAll]);

  // SSE live-nudge: open /api/exports/{id}/events for each non-terminal
  // job. The backend (Phase 8 D1/D2) sends a snapshot event on
  // EXPORT_STATUS_CHANGED and closes; the browser auto-reconnects, so
  // combined with the 3s polling above we get sub-second update latency
  // when the broker pushes and a guaranteed fallback when SSE is
  // unsupported / blocked. Uses fetch-SSE to support auth headers.
  useEffect(() => {
    const activeJobs = jobs.filter((j) => !TERMINAL_STATUSES.has(j.status));
    if (activeJobs.length === 0) return;

    const abortControllers: AbortController[] = [];

    for (const job of activeJobs) {
      const abortController = new AbortController();
      abortControllers.push(abortController);

      const token = getAuthToken();
      const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {};

      (async () => {
        try {
          for await (const event of fetchSSE(
            `/api/exports/${encodeURIComponent(job.exportId)}/events`,
            {
              signal: abortController.signal,
              headers,
            }
          )) {
            // Parse event type from MessageEvent.type (set by fetchSSE)
            if (event.type === "EXPORT_STATUS_CHANGED") {
              void loadAll();
            }
          }
        } catch (error) {
          if ((error as Error).name !== "AbortError") {
            // Silent — 3s polling still drives updates.
          }
        }
      })();
    }

    return () => {
      for (const controller of abortControllers) {
        controller.abort();
      }
    };
  }, [jobs, loadAll]);

  const handleCreate = useCallback(async () => {
    if (!meeting) return;
    setCreateError(null);
    setCreating(true);
    try {
      const input: CreateExportInput = {
        format,
        expectedTranscriptVersion: meeting.transcriptVersion ?? 0,
        expectedMinutesVersion: meeting.minutesVersion ?? null,
        includeTranscript,
        includeMinutes,
        includeItems,
        includeSpeakers,
        watermarkText: watermark.trim() ? watermark.trim() : null,
      };
      const job = await createExport(meeting.meetingId, input);
      setJobs((prev) => [job, ...prev]);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setCreateError(apiError.code ? getUserMessage(apiError.code) : "创建导出失败");
    } finally {
      setCreating(false);
    }
  }, [
    meeting,
    format,
    includeTranscript,
    includeMinutes,
    includeItems,
    includeSpeakers,
    watermark,
  ]);

  const handleCancel = useCallback(async (exportId: string) => {
    try {
      await cancelExport(exportId);
      await loadAll();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "取消导出失败");
    }
  }, [loadAll]);

  const closeRevokeDialog = useCallback(() => {
    if (revokeTarget && revokingId === revokeTarget.exportId) return;
    setRevokeTarget(null);
  }, [revokeTarget, revokingId]);

  const handleRevokeConfirm = useCallback(async () => {
    if (!revokeTarget) return;
    setRevokingId(revokeTarget.exportId);
    try {
      await revokeExportLink(revokeTarget.exportId);
      await loadAll();
      setRevokeTarget(null);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "撤销链接失败");
    } finally {
      setRevokingId(null);
    }
  }, [loadAll, revokeTarget]);

  return (
    <main className="page page--dense">
      <header className="page-hero page-hero--compact">
        <div>
          <span className="page-hero__label">导出</span>
          <h1 className="page-hero__title">导出</h1>
          <p className="page-hero__subtitle">{meeting?.title ?? "当前会议"}</p>
        </div>
        <div className="page-hero__actions">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
        </div>
      </header>

      {error && (
        <section className="glass-panel glass-panel--compact error" role="alert">
          {error}
        </section>
      )}

      <section className="glass-panel glass-panel--compact stack">
        <h2 className="card-title">创建导出</h2>
        <div className="form-grid">
          <label>
            格式
            <select
              value={format}
              onChange={(e) => setFormat(e.target.value as ExportFormat)}
              data-testid="export-format-select"
            >
              {Object.entries(FORMAT_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>

          <label>
            水印（可选，最多 200 字符）
            <input
              type="text"
              value={watermark}
              maxLength={200}
              onChange={(e) => setWatermark(e.target.value)}
              data-testid="export-watermark-input"
            />
          </label>

          <fieldset>
            <legend>包含内容</legend>
            <label>
              <input
                type="checkbox"
                checked={includeTranscript}
                onChange={(e) => setIncludeTranscript(e.target.checked)}
              />
              转录
            </label>
            <label>
              <input
                type="checkbox"
                checked={includeMinutes}
                onChange={(e) => setIncludeMinutes(e.target.checked)}
              />
              纪要
            </label>
            <label>
              <input
                type="checkbox"
                checked={includeItems}
                onChange={(e) => setIncludeItems(e.target.checked)}
              />
              待办 / 决策 / 风险
            </label>
            <label>
              <input
                type="checkbox"
                checked={includeSpeakers}
                onChange={(e) => setIncludeSpeakers(e.target.checked)}
              />
              与会人列表
            </label>
          </fieldset>
        </div>

        {createError && (
          <p className="error" role="alert" data-testid="create-error">
            {createError}
          </p>
        )}

        <button
          className="button button--primary"
          disabled={creating || !meeting}
          onClick={handleCreate}
          data-testid="create-export-button"
        >
          {creating ? "创建中..." : "创建导出"}
        </button>
      </section>

      <section className="glass-panel glass-panel--compact stack">
        <h2 className="card-title">导出历史</h2>
        {pending && jobs.length === 0 ? (
          <p className="muted">加载中...</p>
        ) : jobs.length === 0 ? (
          <p className="muted">暂无导出任务。</p>
        ) : (
          <table className="data-table" data-testid="exports-table">
            <thead>
              <tr>
                <th>创建时间</th>
                <th>格式</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <ExportRow
                  key={job.exportId}
                  job={job}
                  onCancel={() => void handleCancel(job.exportId)}
                  onRevoke={() => setRevokeTarget(job)}
                />
              ))}
            </tbody>
          </table>
        )}
      </section>

      {revokeTarget ? (
        <div className="modal-backdrop" role="presentation">
          <section
            className="modal-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="export-revoke-title"
            aria-describedby="export-revoke-description"
          >
            <div className="modal-header">
              <div>
                <h2 id="export-revoke-title" className="card-title">撤销下载链接</h2>
                <p id="export-revoke-description" className="muted">
                  撤销后将不可恢复，已分享的下载链接会立即失效。
                </p>
              </div>
              <button
                className="button button--ghost"
                type="button"
                onClick={closeRevokeDialog}
                disabled={revokingId === revokeTarget.exportId}
              >
                取消
              </button>
            </div>
            <dl className="grid">
              <div>
                <dt className="muted">格式</dt>
                <dd>{FORMAT_LABELS[revokeTarget.format]}</dd>
              </div>
              <div>
                <dt className="muted">创建时间</dt>
                <dd>{formatTimestamp(revokeTarget.createdAt)}</dd>
              </div>
            </dl>
            <div className="modal-actions" aria-live="polite">
              <button
                className="button"
                type="button"
                onClick={closeRevokeDialog}
                disabled={revokingId === revokeTarget.exportId}
              >
                取消
              </button>
              <button
                className="button button--danger"
                type="button"
                onClick={() => void handleRevokeConfirm()}
                disabled={revokingId === revokeTarget.exportId}
              >
                {revokingId === revokeTarget.exportId ? "撤销中..." : "确认撤销"}
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </main>
  );
}

interface ExportRowProps {
  job: ExportJob;
  onCancel: () => void;
  onRevoke: () => void;
}

function ExportRow({ job, onCancel, onRevoke }: ExportRowProps) {
  const isTerminal = TERMINAL_STATUSES.has(job.status);
  const canDownload =
    job.status === "SUCCEEDED" && !job.revoked && job.downloadUrl;

  return (
    <tr data-testid={`export-row-${job.exportId}`}>
      <td>{formatTimestamp(job.createdAt)}</td>
      <td>{FORMAT_LABELS[job.format]}</td>
      <td>
        <span className={`status-badge status-${job.status.toLowerCase()}`}>
          {STATUS_LABELS[job.status]}
        </span>
        {job.stale && (
          <span
            className="badge warn"
            title="导出后会议内容已变更"
            data-testid={`export-stale-${job.exportId}`}
          >
            ⚠️ 内容已变更
          </span>
        )}
        {job.errorCode && (
          <span className="muted" data-testid={`export-error-${job.exportId}`}>
            （{getUserMessage(job.errorCode)}）
          </span>
        )}
      </td>
      <td className="actions">
        {canDownload && (
          <a
            className="button"
            href={job.downloadUrl ?? "#"}
            target="_blank"
            rel="noopener noreferrer"
            data-testid={`export-download-${job.exportId}`}
          >
            下载
          </a>
        )}
        {!isTerminal && (
          <button
            className="button"
            onClick={onCancel}
            data-testid={`export-cancel-${job.exportId}`}
          >
            取消
          </button>
        )}
        {job.status === "SUCCEEDED" && !job.revoked && (
          <button
            className="button button--danger"
            onClick={onRevoke}
            data-testid={`export-revoke-${job.exportId}`}
          >
            撤销链接
          </button>
        )}
      </td>
    </tr>
  );
}
