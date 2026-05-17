import { useCallback, useEffect, useMemo, useState } from "react";
import {
  createLegalHold,
  listLegalHolds,
  releaseLegalHold,
  type ApiClientError,
  type CreateLegalHoldInput,
  type LegalHold,
  type LegalHoldScopeType,
} from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

const SCOPE_LABELS: Record<LegalHoldScopeType, string> = {
  MEETING: "会议",
  DOCUMENT: "文档",
  SPEAKER_PROFILE: "声纹档案",
  PROJECT: "项目",
};

const STATUS_LABELS: Record<LegalHold["status"], string> = {
  ACTIVE: "生效中",
  RELEASED: "已释放",
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

export function LegalHoldsPage() {
  const [holds, setHolds] = useState<LegalHold[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Create form state
  const [showCreate, setShowCreate] = useState(false);
  const [scopeType, setScopeType] = useState<LegalHoldScopeType>("MEETING");
  const [scopeId, setScopeId] = useState("");
  const [reason, setReason] = useState("");
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const loadAll = useCallback(async () => {
    setError(null);
    setPending(true);
    try {
      const resp = await listLegalHolds();
      setHolds(resp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载法定保全失败");
    } finally {
      setPending(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const sortedHolds = useMemo(
    () => [...holds].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [holds],
  );

  const handleCreate = useCallback(async () => {
    if (!scopeId.trim() || !reason.trim()) {
      setCreateError("请填写 scopeId 和原因");
      return;
    }
    setCreateError(null);
    setCreating(true);
    try {
      const input: CreateLegalHoldInput = {
        scopeType,
        scopeId: scopeId.trim(),
        reason: reason.trim(),
      };
      const created = await createLegalHold(input);
      setHolds((prev) => [created, ...prev]);
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

  const handleRelease = useCallback(async (hold: LegalHold) => {
    const userReason = window.prompt(
      `释放 legal hold ${hold.legalHoldId} 的原因：`,
      "",
    );
    if (!userReason || !userReason.trim()) return;
    try {
      await releaseLegalHold(hold.legalHoldId, { reason: userReason.trim() });
      await loadAll();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "释放失败");
    }
  }, [loadAll]);

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">法定保全（Legal Holds）</h1>
          <p className="muted">放置 / 释放对会议、文档、声纹档案的法定保全。命中保全的对象不可被删除或导出。</p>
        </div>
        <button
          className="button primary"
          onClick={() => setShowCreate((v) => !v)}
          data-testid="toggle-create-legal-hold"
        >
          {showCreate ? "取消创建" : "放置保全"}
        </button>
      </div>

      {error && (
        <section className="card error" role="alert" data-testid="lh-error">
          {error}
        </section>
      )}

      {showCreate && (
        <section className="card" data-testid="lh-create-form">
          <h2 className="card-title">放置法定保全</h2>
          <div className="form-grid">
            <label>
              范围类型
              <select
                value={scopeType}
                onChange={(e) => setScopeType(e.target.value as LegalHoldScopeType)}
                data-testid="lh-scope-type"
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
                placeholder="例：mtg_xxx"
                data-testid="lh-scope-id"
              />
            </label>
            <label>
              原因
              <input
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="例：监管调查 / 诉讼保全 ..."
                data-testid="lh-reason"
              />
            </label>
          </div>
          {createError && (
            <p className="error" role="alert" data-testid="lh-create-error">
              {createError}
            </p>
          )}
          <button
            className="button primary"
            disabled={creating}
            onClick={handleCreate}
            data-testid="lh-create-submit"
          >
            {creating ? "创建中..." : "提交"}
          </button>
        </section>
      )}

      <section className="card">
        <h2 className="card-title">保全列表</h2>
        {pending && holds.length === 0 ? (
          <p className="muted">加载中...</p>
        ) : holds.length === 0 ? (
          <p className="muted">暂无法定保全。</p>
        ) : (
          <table className="data-table" data-testid="lh-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>范围</th>
                <th>状态</th>
                <th>原因</th>
                <th>放置时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {sortedHolds.map((hold) => (
                <tr key={hold.legalHoldId} data-testid={`lh-row-${hold.legalHoldId}`}>
                  <td className="muted">{hold.legalHoldId}</td>
                  <td>
                    <strong>{SCOPE_LABELS[hold.scopeType]}</strong>
                    <br />
                    <span className="muted">{hold.scopeId}</span>
                  </td>
                  <td>
                    <span className={`status-badge status-${hold.status.toLowerCase()}`}>
                      {STATUS_LABELS[hold.status]}
                    </span>
                  </td>
                  <td>{hold.reason}</td>
                  <td>{formatTimestamp(hold.createdAt)}</td>
                  <td>
                    {hold.status === "ACTIVE" && (
                      <button
                        className="button"
                        onClick={() => void handleRelease(hold)}
                        data-testid={`lh-release-${hold.legalHoldId}`}
                      >
                        释放
                      </button>
                    )}
                    {hold.status === "RELEASED" && hold.releaseReason && (
                      <span className="muted">
                        已释放（{hold.releaseReason}）
                      </span>
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
