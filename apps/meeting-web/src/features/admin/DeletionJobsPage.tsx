import { useCallback, useEffect, useMemo, useState } from "react";
import {
  createDeletionJob,
  listDeletionJobs,
  type ApiClientError,
  type CreateDeletionJobInput,
  type DeletionJob,
  type DeletionScopeType,
} from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

const SCOPE_LABELS: Record<DeletionScopeType, string> = {
  MEETING: "会议",
  DOCUMENT: "文档",
  SPEAKER_PROFILE: "声纹档案",
  USER: "用户",
  PROJECT: "项目",
  TENANT: "整租户（高危）",
};

const STATUS_LABELS: Record<DeletionJob["status"], string> = {
  REQUESTED: "排队中",
  PENDING_APPROVAL: "待审批",
  RUNNING: "执行中",
  SUCCEEDED: "已完成",
  PARTIAL_FAILED: "部分失败",
  FAILED: "失败",
  BLOCKED_BY_LEGAL_HOLD: "被法定保全阻断",
};

const TERMINAL_STATUSES: ReadonlySet<DeletionJob["status"]> = new Set([
  "SUCCEEDED",
  "PARTIAL_FAILED",
  "FAILED",
  "BLOCKED_BY_LEGAL_HOLD",
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

export function DeletionJobsPage() {
  const [jobs, setJobs] = useState<DeletionJob[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [showCreate, setShowCreate] = useState(false);
  const [scopeType, setScopeType] = useState<DeletionScopeType>("MEETING");
  const [scopeId, setScopeId] = useState("");
  const [reason, setReason] = useState("");
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const hasActiveJob = useMemo(
    () => jobs.some((j) => !TERMINAL_STATUSES.has(j.status)),
    [jobs],
  );

  const loadAll = useCallback(async () => {
    setError(null);
    setPending(true);
    try {
      const resp = await listDeletionJobs();
      setJobs(resp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载删除任务失败");
    } finally {
      setPending(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  // Auto-refresh while any job is non-terminal so the executor's
  // progress is visible without a manual refresh.
  useEffect(() => {
    if (!hasActiveJob) return;
    const handle = window.setInterval(() => void loadAll(), 3000);
    return () => window.clearInterval(handle);
  }, [hasActiveJob, loadAll]);

  const sortedJobs = useMemo(
    () => [...jobs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [jobs],
  );

  const handleCreate = useCallback(async () => {
    if (!scopeId.trim() || !reason.trim()) {
      setCreateError("请填写 scopeId 和原因");
      return;
    }
    const confirmMsg = scopeType === "TENANT"
      ? `你正在请求删除整个租户的数据。\n输入 "DELETE-TENANT" 确认：`
      : `确认提交删除任务？\nscope: ${SCOPE_LABELS[scopeType]} / ${scopeId.trim()}\n原因: ${reason.trim()}`;
    const confirmed = scopeType === "TENANT"
      ? window.prompt(confirmMsg) === "DELETE-TENANT"
      : window.confirm(confirmMsg);
    if (!confirmed) return;

    setCreateError(null);
    setCreating(true);
    try {
      const input: CreateDeletionJobInput = {
        scopeType,
        scopeId: scopeId.trim(),
        reason: reason.trim(),
      };
      const created = await createDeletionJob(input);
      setJobs((prev) => [created, ...prev]);
      setScopeId("");
      setReason("");
      setShowCreate(false);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setCreateError(apiError.code ? getUserMessage(apiError.code) : "创建失败");
    } finally {
      setCreating(false);
    }
  }, [scopeType, scopeId, reason]);

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">数据删除任务</h1>
          <p className="muted">
            异步删除会议、文档、声纹档案等数据。完成后生成 deletion certificate 作为审计证明。
          </p>
        </div>
        <button
          className="button danger"
          onClick={() => setShowCreate((v) => !v)}
          data-testid="toggle-create-deletion-job"
        >
          {showCreate ? "取消创建" : "新建删除任务"}
        </button>
      </div>

      {error && (
        <section className="card error" role="alert" data-testid="dj-error">
          {error}
        </section>
      )}

      {showCreate && (
        <section className="card" data-testid="dj-create-form">
          <h2 className="card-title">新建删除任务</h2>
          <p className="muted">
            提交后系统将先检查法定保全；命中保全的任务会立即标记为 BLOCKED_BY_LEGAL_HOLD 并写入审计。
          </p>
          <div className="form-grid">
            <label>
              范围类型
              <select
                value={scopeType}
                onChange={(e) => setScopeType(e.target.value as DeletionScopeType)}
                data-testid="dj-scope-type"
              >
                {Object.entries(SCOPE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
            <label>
              范围 ID
              <input
                type="text"
                value={scopeId}
                onChange={(e) => setScopeId(e.target.value)}
                placeholder="例：mtg_xxx / doc_xxx / u_xxx"
                data-testid="dj-scope-id"
              />
            </label>
            <label>
              原因
              <input
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="例：GDPR Article 17 数据擦除请求"
                data-testid="dj-reason"
              />
            </label>
          </div>
          {createError && (
            <p className="error" role="alert" data-testid="dj-create-error">
              {createError}
            </p>
          )}
          <button
            className="button danger"
            disabled={creating}
            onClick={handleCreate}
            data-testid="dj-create-submit"
          >
            {creating ? "提交中..." : "提交"}
          </button>
        </section>
      )}

      <section className="card">
        <h2 className="card-title">删除任务列表</h2>
        {pending && jobs.length === 0 ? (
          <p className="muted">加载中...</p>
        ) : jobs.length === 0 ? (
          <p className="muted">暂无删除任务。</p>
        ) : (
          <table className="data-table" data-testid="dj-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>范围</th>
                <th>状态</th>
                <th>申请人</th>
                <th>创建时间</th>
                <th>错误码</th>
              </tr>
            </thead>
            <tbody>
              {sortedJobs.map((job) => (
                <tr key={job.deletionJobId} data-testid={`dj-row-${job.deletionJobId}`}>
                  <td className="muted">{job.deletionJobId}</td>
                  <td>
                    <strong>{SCOPE_LABELS[job.scopeType]}</strong>
                    <br />
                    <span className="muted">{job.scopeId}</span>
                  </td>
                  <td>
                    <span className={`status-badge status-${job.status.toLowerCase()}`}>
                      {STATUS_LABELS[job.status]}
                    </span>
                  </td>
                  <td>{job.requestedBy}</td>
                  <td>{formatTimestamp(job.createdAt)}</td>
                  <td>
                    {job.errorCode ? (
                      <span className="muted" data-testid={`dj-error-${job.deletionJobId}`}>
                        {getUserMessage(job.errorCode)}
                      </span>
                    ) : (
                      "—"
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
