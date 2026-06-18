import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
  const [confirmTarget, setConfirmTarget] = useState<CreateDeletionJobInput | null>(null);
  const [tenantConfirmPhrase, setTenantConfirmPhrase] = useState("");
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const tenantConfirmRef = useRef<HTMLInputElement | null>(null);

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

  const openCreateConfirmation = useCallback(() => {
    if (!scopeId.trim() || !reason.trim()) {
      setCreateError("请填写目标编号和原因");
      return;
    }
    setCreateError(null);
    setConfirmError(null);
    setTenantConfirmPhrase("");
    setConfirmTarget({
      scopeType,
      scopeId: scopeId.trim(),
      reason: reason.trim(),
    });
  }, [scopeType, scopeId, reason]);

  const closeCreateConfirmation = useCallback(() => {
    if (creating) return;
    setConfirmTarget(null);
    setConfirmError(null);
    setTenantConfirmPhrase("");
  }, [creating]);

  const handleCreateConfirm = useCallback(async () => {
    if (!confirmTarget) return;
    if (confirmTarget.scopeType === "TENANT" && tenantConfirmPhrase !== "DELETE-TENANT") {
      setConfirmError("请输入 DELETE-TENANT 确认整租户删除");
      tenantConfirmRef.current?.focus();
      return;
    }

    setConfirmError(null);
    setCreating(true);
    try {
      const created = await createDeletionJob(confirmTarget);
      setJobs((prev) => [created, ...prev]);
      setScopeId("");
      setReason("");
      setShowCreate(false);
      setConfirmTarget(null);
      setTenantConfirmPhrase("");
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setCreateError(apiError.code ? getUserMessage(apiError.code) : "创建失败");
    } finally {
      setCreating(false);
    }
  }, [confirmTarget, tenantConfirmPhrase]);

  return (
    <main className="page page--dense">
      <header className="page-hero page-hero--compact">
        <div>
          <span className="page-hero__label">数据留存</span>
          <h1 className="page-hero__title">数据删除任务</h1>
          <p className="page-hero__subtitle">
            异步删除会议、文档、声纹档案等数据。完成后生成删除证明作为审计依据。
          </p>
        </div>
        <div className="page-hero__actions">
          <button
            className="button button--danger"
            onClick={() => setShowCreate((v) => !v)}
            data-testid="toggle-create-deletion-job"
          >
            {showCreate ? "取消创建" : "新建删除任务"}
          </button>
        </div>
      </header>

      {error && (
        <section className="glass-panel glass-panel--compact error" role="alert" data-testid="dj-error">
          {error}
        </section>
      )}

      {showCreate && (
        <section className="glass-panel glass-panel--compact stack" data-testid="dj-create-form">
          <h2 className="card-title">新建删除任务</h2>
          <p className="muted">
            提交后系统将先检查法定保全；命中保全的任务会立即标记为被法定保全阻断并写入审计。
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
              目标编号
              <input
                type="text"
                value={scopeId}
                onChange={(e) => setScopeId(e.target.value)}
                placeholder="例：需要删除的目标编号"
                data-testid="dj-scope-id"
              />
            </label>
            <label>
              原因
              <input
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="例：用户发起的数据擦除请求"
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
            className="button button--danger"
            disabled={creating}
            onClick={openCreateConfirmation}
            data-testid="dj-create-submit"
          >
            {creating ? "提交中..." : "提交"}
          </button>
        </section>
      )}

      <section className="glass-panel glass-panel--compact stack">
        <h2 className="card-title">删除任务列表</h2>
        {pending && jobs.length === 0 ? (
          <p className="muted">加载中...</p>
        ) : jobs.length === 0 ? (
          <p className="muted">暂无删除任务。</p>
        ) : (
          <table className="data-table" data-testid="dj-table">
            <thead>
              <tr>
                <th>任务</th>
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
                  <td className="muted">任务已记录</td>
                  <td>
                    <strong>{SCOPE_LABELS[job.scopeType]}</strong>
                    <br />
                    <span className="muted">目标已记录</span>
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

      {confirmTarget ? (
        <div className="modal-backdrop" role="presentation">
          <section
            className="modal-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="dj-confirm-title"
            aria-describedby="dj-confirm-description"
          >
            <div className="modal-header">
              <div>
                <h2 id="dj-confirm-title" className="card-title">确认删除任务</h2>
                <p id="dj-confirm-description" className="muted">
                  提交后系统将检查法定保全，并异步清理命中的数据。
                </p>
              </div>
              <button
                className="button button--ghost"
                type="button"
                onClick={closeCreateConfirmation}
                disabled={creating}
              >
                取消
              </button>
            </div>
            <dl className="grid">
              <div>
                <dt className="muted">范围</dt>
                <dd>{SCOPE_LABELS[confirmTarget.scopeType]}</dd>
              </div>
              <div>
                <dt className="muted">目标编号</dt>
                <dd>已记录</dd>
              </div>
              <div>
                <dt className="muted">原因</dt>
                <dd>{confirmTarget.reason}</dd>
              </div>
            </dl>
            {confirmTarget.scopeType === "TENANT" ? (
              <div className="field">
                <label className="field__label" htmlFor="dj-tenant-confirmation">
                  确认口令
                </label>
                <input
                  id="dj-tenant-confirmation"
                  className="field__input"
                  ref={tenantConfirmRef}
                  name="tenantDeletionConfirmation"
                  autoComplete="off"
                  value={tenantConfirmPhrase}
                  onChange={(event) => setTenantConfirmPhrase(event.target.value)}
                  aria-invalid={confirmError ? "true" : "false"}
                  aria-describedby={confirmError ? "dj-confirm-error" : "dj-confirm-hint"}
                />
                <span className="muted" id="dj-confirm-hint">
                  输入 DELETE-TENANT 才能提交整租户删除任务。
                </span>
              </div>
            ) : null}
            {confirmError ? (
              <p className="field__error" id="dj-confirm-error" role="alert">
                {confirmError}
              </p>
            ) : null}
            <div className="modal-actions" aria-live="polite">
              <button
                className="button"
                type="button"
                onClick={closeCreateConfirmation}
                disabled={creating}
              >
                取消
              </button>
              <button
                className="button button--danger"
                type="button"
                onClick={() => void handleCreateConfirm()}
                disabled={creating}
              >
                {creating ? "提交中..." : "确认提交"}
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </main>
  );
}
