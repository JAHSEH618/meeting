import { useCallback, useEffect, useState } from "react";
import {
  listAuditEvents,
  type ApiClientError,
  type AuditEventT,
  type AuditQueryParams,
} from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

const ACTION_OPTIONS = [
  "CREATE",
  "READ",
  "UPDATE",
  "DELETE",
  "EXPORT",
  "LOGIN",
  "LOGOUT",
  "LEGAL_HOLD_PLACE",
  "LEGAL_HOLD_RELEASE",
  "DELETION_REQUEST",
  "DELETION_EXECUTE",
  "BREAK_GLASS_REQUEST",
  "BREAK_GLASS_APPROVE",
  "BREAK_GLASS_REJECT",
  "BREAK_GLASS_ACCESS",
] as const;

const RESULT_OPTIONS = ["SUCCESS", "FAILURE", "BLOCKED", "DENIED"] as const;

const ACTION_LABELS: Record<string, string> = {
  CREATE: "创建",
  READ: "读取",
  UPDATE: "更新",
  DELETE: "删除",
  EXPORT: "导出",
  LOGIN: "登录",
  LOGOUT: "退出登录",
  LEGAL_HOLD_PLACE: "放置法定保全",
  LEGAL_HOLD_RELEASE: "释放法定保全",
  DELETION_REQUEST: "申请删除",
  DELETION_EXECUTE: "执行删除",
  BREAK_GLASS_REQUEST: "申请应急访问",
  BREAK_GLASS_APPROVE: "批准应急访问",
  BREAK_GLASS_REJECT: "拒绝应急访问",
  BREAK_GLASS_ACCESS: "应急访问",
};

const RESULT_LABELS: Record<string, string> = {
  SUCCESS: "成功",
  FAILURE: "失败",
  BLOCKED: "已阻断",
  DENIED: "已拒绝",
};

const RESOURCE_LABELS: Record<string, string> = {
  MEETING: "会议",
  DOCUMENT: "文档",
  LEGAL_HOLD: "法定保全",
  EXPORT: "导出任务",
  DELETION_JOB: "删除任务",
  BREAK_GLASS: "应急访问",
  SPEAKER_PROFILE: "声纹档案",
  TENANT: "租户",
  USER: "用户",
};

const RESOURCE_QUERY_ALIASES: Record<string, string> = Object.fromEntries(
  Object.entries(RESOURCE_LABELS).map(([value, label]) => [label, value]),
);

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(
    date.getDate(),
  ).padStart(2, "0")} ${String(date.getHours()).padStart(2, "0")}:${String(
    date.getMinutes(),
  ).padStart(2, "0")}:${String(date.getSeconds()).padStart(2, "0")}`;
}

function formatAuditReason(reason: string | null | undefined): string {
  if (!reason) return "";
  if (reason === "blocked by legal hold") return "被法定保全阻断";
  return reason;
}

export function AuditEventsPage() {
  const [events, setEvents] = useState<AuditEventT[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Filter form state
  const [actorUserId, setActorUserId] = useState("");
  const [resourceType, setResourceType] = useState("");
  const [action, setAction] = useState("");
  const [result, setResult] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const fetchAt = useCallback(async (params: AuditQueryParams) => {
    setError(null);
    setPending(true);
    try {
      const resp = await listAuditEvents(params);
      setEvents(resp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载审计事件失败");
    } finally {
      setPending(false);
    }
  }, []);

  useEffect(() => {
    void fetchAt({});
  }, [fetchAt]);

  const handleFilter = useCallback(() => {
    const normalizedResourceType = resourceType.trim();
    void fetchAt({
      actorUserId: actorUserId.trim() || undefined,
      resourceType: (RESOURCE_QUERY_ALIASES[normalizedResourceType] ?? normalizedResourceType) || undefined,
      action: action || undefined,
      result: result || undefined,
      from: from ? `${from}T00:00:00Z` : undefined,
      to: to ? `${to}T23:59:59Z` : undefined,
      limit: 50,
    });
  }, [actorUserId, resourceType, action, result, from, to, fetchAt]);

  return (
    <main className="page page--dense">
      <header className="page-hero page-hero--compact">
        <div>
          <span className="page-hero__label">审计日志</span>
          <h1 className="page-hero__title">审计事件</h1>
          <p className="page-hero__subtitle">
            查询合规相关写操作（法定保全、删除、应急访问、导出）的审计日志。
            时间窗最大 90 天。
          </p>
        </div>
      </header>

      <section className="glass-panel glass-panel--compact stack" data-testid="audit-filter-form">
        <h2 className="card-title">筛选</h2>
        <div className="form-grid">
          <label>
            操作人编号
            <input
              type="text"
              value={actorUserId}
              onChange={(e) => setActorUserId(e.target.value)}
              placeholder="例：管理员账号"
              data-testid="audit-actor-input"
            />
          </label>
          <label>
            资源类型
            <input
              type="text"
              value={resourceType}
              onChange={(e) => setResourceType(e.target.value)}
              placeholder="例：会议 / 法定保全 / 导出"
              data-testid="audit-resource-type-input"
            />
          </label>
          <label>
            动作
            <select
              value={action}
              onChange={(e) => setAction(e.target.value)}
              data-testid="audit-action-select"
            >
              <option value="">（全部）</option>
              {ACTION_OPTIONS.map((a) => (
                <option key={a} value={a}>{ACTION_LABELS[a]}</option>
              ))}
            </select>
          </label>
          <label>
            结果
            <select
              value={result}
              onChange={(e) => setResult(e.target.value)}
              data-testid="audit-result-select"
            >
              <option value="">（全部）</option>
              {RESULT_OPTIONS.map((r) => (
                <option key={r} value={r}>{RESULT_LABELS[r]}</option>
              ))}
            </select>
          </label>
          <label>
            起始日期
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              data-testid="audit-from-input"
            />
          </label>
          <label>
            截止日期
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              data-testid="audit-to-input"
            />
          </label>
        </div>
        <button
          className="button button--primary"
          onClick={handleFilter}
          disabled={pending}
          data-testid="audit-filter-submit"
        >
          {pending ? "查询中..." : "查询"}
        </button>
      </section>

      {error && (
        <section className="glass-panel glass-panel--compact error" role="alert" data-testid="audit-error">
          {error}
        </section>
      )}

      <section className="glass-panel glass-panel--compact stack">
        <h2 className="card-title">事件列表（{events.length} 条）</h2>
        {events.length === 0 ? (
          <p className="muted">暂无匹配的审计事件。</p>
        ) : (
          <table className="data-table" data-testid="audit-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>操作人</th>
                <th>动作</th>
                <th>资源</th>
                <th>结果</th>
                <th>原因 / 备注</th>
              </tr>
            </thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.auditEventId} data-testid={`audit-row-${event.auditEventId}`}>
                  <td className="muted">{formatTimestamp(event.createdAt)}</td>
                  <td>{event.actorUserId ? "已记录" : "系统"}</td>
                  <td>
                    {ACTION_LABELS[event.action] ?? "其他操作"}
                  </td>
                  <td>
                    <strong>{RESOURCE_LABELS[event.resourceType] ?? "资源"}</strong>
                    {event.resourceId && (
                      <>
                        <br />
                        <span className="muted">资源编号已记录</span>
                      </>
                    )}
                  </td>
                  <td>
                    <span className={`status-badge status-${event.result.toLowerCase()}`}>
                      {RESULT_LABELS[event.result] ?? "未知结果"}
                    </span>
                  </td>
                  <td className="muted">{formatAuditReason(event.reason)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
