import { useCallback, useEffect, useMemo, useState } from "react";
import {
  approveBreakGlassRequest,
  createBreakGlassRequest,
  listBreakGlassRequests,
  rejectBreakGlassRequest,
  type ApiClientError,
  type BreakGlassRequestT,
  type BreakGlassStatus,
  type CreateBreakGlassInput,
} from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

const SCOPE_OPTIONS = ["MEETING", "DOCUMENT", "TENANT"] as const;

const STATUS_LABELS: Record<BreakGlassStatus, string> = {
  PENDING: "待审批",
  APPROVED: "已批准",
  REJECTED: "已拒绝",
  EXPIRED: "已过期",
  REVOKED: "已撤销",
};

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

export function BreakGlassPage() {
  const [requests, setRequests] = useState<BreakGlassRequestT[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [showCreate, setShowCreate] = useState(false);
  const [scopeType, setScopeType] = useState<string>("MEETING");
  const [scopeId, setScopeId] = useState("");
  const [reason, setReason] = useState("");
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const loadAll = useCallback(async () => {
    setError(null);
    setPending(true);
    try {
      const resp = await listBreakGlassRequests();
      setRequests(resp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载紧急访问申请失败");
    } finally {
      setPending(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const sorted = useMemo(
    () => [...requests].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [requests],
  );

  const handleCreate = useCallback(async () => {
    if (!scopeId.trim() || !reason.trim()) {
      setCreateError("请填写 scopeId 和原因");
      return;
    }
    setCreateError(null);
    setCreating(true);
    try {
      const input: CreateBreakGlassInput = {
        scopeType,
        scopeId: scopeId.trim(),
        reason: reason.trim(),
      };
      const created = await createBreakGlassRequest(input);
      setRequests((prev) => [created, ...prev]);
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

  const handleApprove = useCallback(async (req: BreakGlassRequestT) => {
    if (!window.confirm(`批准 ${req.breakGlassRequestId} 的紧急访问申请？默认窗口 4 小时。`)) return;
    try {
      await approveBreakGlassRequest(req.breakGlassRequestId);
      await loadAll();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "审批失败");
    }
  }, [loadAll]);

  const handleReject = useCallback(async (req: BreakGlassRequestT) => {
    const rejectReason = window.prompt(`拒绝 ${req.breakGlassRequestId} 的原因：`, "");
    if (!rejectReason || !rejectReason.trim()) return;
    try {
      await rejectBreakGlassRequest(req.breakGlassRequestId, rejectReason.trim());
      await loadAll();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "拒绝失败");
    }
  }, [loadAll]);

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">应急访问</h1>
          <p className="muted">
            申请、审批和拒绝对 CONFIDENTIAL / SECRET 资源的临时访问。审批默认 4 小时窗口；过期后自动失效。
            申请人不能审批自己的申请。
          </p>
        </div>
        <button
          className="button primary"
          onClick={() => setShowCreate((v) => !v)}
          data-testid="toggle-create-bg"
        >
          {showCreate ? "取消申请" : "提交新申请"}
        </button>
      </div>

      {error && (
        <section className="card error" role="alert" data-testid="bg-error">
          {error}
        </section>
      )}

      {showCreate && (
        <section className="card" data-testid="bg-create-form">
          <h2 className="card-title">紧急访问申请</h2>
          <div className="form-grid">
            <label>
              范围类型
              <select
                value={scopeType}
                onChange={(e) => setScopeType(e.target.value)}
                data-testid="bg-scope-type"
              >
                {SCOPE_OPTIONS.map((value) => (
                  <option key={value} value={value}>{value}</option>
                ))}
              </select>
            </label>
            <label>
              范围 ID
              <input
                type="text"
                value={scopeId}
                onChange={(e) => setScopeId(e.target.value)}
                placeholder="例：mtg_xxx"
                data-testid="bg-scope-id"
              />
            </label>
            <label>
              原因
              <input
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="例：调查 incident #1234"
                data-testid="bg-reason"
              />
            </label>
          </div>
          {createError && (
            <p className="error" role="alert" data-testid="bg-create-error">
              {createError}
            </p>
          )}
          <button
            className="button primary"
            disabled={creating}
            onClick={handleCreate}
            data-testid="bg-create-submit"
          >
            {creating ? "提交中..." : "提交申请"}
          </button>
        </section>
      )}

      <section className="card">
        <h2 className="card-title">申请列表</h2>
        {pending && requests.length === 0 ? (
          <p className="muted">加载中...</p>
        ) : requests.length === 0 ? (
          <p className="muted">暂无紧急访问申请。</p>
        ) : (
          <table className="data-table" data-testid="bg-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>范围</th>
                <th>状态</th>
                <th>申请人</th>
                <th>窗口</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((req) => (
                <tr key={req.breakGlassRequestId} data-testid={`bg-row-${req.breakGlassRequestId}`}>
                  <td className="muted">{req.breakGlassRequestId}</td>
                  <td>
                    <strong>{req.scopeType}</strong>
                    <br />
                    <span className="muted">{req.scopeId}</span>
                  </td>
                  <td>
                    <span className={`status-badge status-${req.status.toLowerCase()}`}>
                      {STATUS_LABELS[req.status]}
                    </span>
                  </td>
                  <td>{req.requesterId}</td>
                  <td>
                    {req.validFrom && req.validUntil ? (
                      <>
                        <span className="muted">
                          {formatTimestamp(req.validFrom)} → {formatTimestamp(req.validUntil)}
                        </span>
                      </>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td>
                    {req.status === "PENDING" && (
                      <>
                        <button
                          className="button primary"
                          onClick={() => void handleApprove(req)}
                          data-testid={`bg-approve-${req.breakGlassRequestId}`}
                        >
                          批准
                        </button>
                        <button
                          className="button danger"
                          onClick={() => void handleReject(req)}
                          data-testid={`bg-reject-${req.breakGlassRequestId}`}
                        >
                          拒绝
                        </button>
                      </>
                    )}
                    {req.status === "REJECTED" && req.rejectReason && (
                      <span className="muted">{req.rejectReason}</span>
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
