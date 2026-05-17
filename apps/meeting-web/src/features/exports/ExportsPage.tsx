import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  cancelExport,
  createExport,
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

  const handleRevoke = useCallback(async (exportId: string) => {
    if (!window.confirm("撤销下载链接后将不可恢复，确认要撤销？")) return;
    try {
      await revokeExportLink(exportId);
      await loadAll();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "撤销链接失败");
    }
  }, [loadAll]);

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">导出</h1>
          <p className="muted">{meeting?.title ?? meetingId}</p>
        </div>
        <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
      </div>

      {error && (
        <section className="card error" role="alert">
          {error}
        </section>
      )}

      <section className="card">
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
          className="button primary"
          disabled={creating || !meeting}
          onClick={handleCreate}
          data-testid="create-export-button"
        >
          {creating ? "创建中..." : "创建导出"}
        </button>
      </section>

      <section className="card">
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
                  onRevoke={() => void handleRevoke(job.exportId)}
                />
              ))}
            </tbody>
          </table>
        )}
      </section>
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
            className="button danger"
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
