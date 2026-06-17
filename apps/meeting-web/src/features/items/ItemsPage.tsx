import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  acceptItem,
  listActionItems,
  listDecisions,
  listRisks,
  rejectItem,
  type ActionItem,
  type Decision,
  type ItemEvidence,
  type ItemKind,
  type Risk,
} from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

type Acceptance = "DRAFT" | "ACCEPTED" | "REJECTED" | "NEEDS_REVIEW" | string;

export function ItemsPage() {
  const { meetingId = "" } = useParams();
  const [actions, setActions] = useState<ActionItem[]>([]);
  const [decisions, setDecisions] = useState<Decision[]>([]);
  const [risks, setRisks] = useState<Risk[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [actionsResp, decisionsResp, risksResp] = await Promise.all([
        listActionItems(meetingId),
        listDecisions(meetingId),
        listRisks(meetingId),
      ]);
      setActions(actionsResp.items);
      setDecisions(decisionsResp.items);
      setRisks(risksResp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载失败");
    } finally {
      setLoading(false);
    }
  }, [meetingId]);

  useEffect(() => {
    if (!meetingId) return;
    void reload();
  }, [meetingId, reload]);

  const handleAccept = async (kind: ItemKind, itemId: string) => {
    setPendingId(itemId);
    try {
      await acceptItem(meetingId, kind, itemId);
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "操作失败");
    } finally {
      setPendingId(null);
    }
  };

  const handleReject = async (kind: ItemKind, itemId: string) => {
    setPendingId(itemId);
    try {
      await rejectItem(meetingId, kind, itemId);
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "操作失败");
    } finally {
      setPendingId(null);
    }
  };

  return (
    <main className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">ITEMS</span>
          <h1 className="page-hero__title">事项</h1>
          <p className="page-hero__subtitle">{meetingId}</p>
        </div>
        <div className="page-hero__actions">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/transcript`}>查看转录</Link>
          <Link className="button" to={`/meetings/${meetingId}/minutes`}>查看纪要</Link>
        </div>
      </header>

      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}

      <section className="glass-panel stack" aria-label="待办">
        <div className="toolbar">
          <strong>待办</strong>
          <span className="muted">{actions.length} 项</span>
        </div>
        {actions.length === 0 && !loading ? <p className="muted">暂无待办</p> : null}
        {actions.map((item) => (
          <ItemCard
            key={item.id}
            id={item.id}
            title={item.title}
            description={item.description}
            evidence={item.evidence}
            acceptanceStatus={item.acceptanceStatus}
            staleStatus={item.staleStatus}
            badges={[
              item.priority ? `优先级 ${item.priority}` : null,
              `状态 ${item.status}`,
              item.ownerRawText ? `负责人 ${item.ownerRawText}` : null,
            ]}
            kind="action-items"
            pending={pendingId === item.id}
            onAccept={handleAccept}
            onReject={handleReject}
          />
        ))}
      </section>

      <section className="glass-panel stack" aria-label="决策">
        <div className="toolbar">
          <strong>决策</strong>
          <span className="muted">{decisions.length} 项</span>
        </div>
        {decisions.length === 0 && !loading ? <p className="muted">暂无决策</p> : null}
        {decisions.map((item) => (
          <ItemCard
            key={item.id}
            id={item.id}
            title={item.title}
            description={item.description}
            evidence={item.evidence}
            acceptanceStatus={item.acceptanceStatus}
            staleStatus={item.staleStatus}
            badges={[`状态 ${item.status}`]}
            kind="decisions"
            pending={pendingId === item.id}
            onAccept={handleAccept}
            onReject={handleReject}
          />
        ))}
      </section>

      <section className="glass-panel stack" aria-label="风险">
        <div className="toolbar">
          <strong>风险</strong>
          <span className="muted">{risks.length} 项</span>
        </div>
        {risks.length === 0 && !loading ? <p className="muted">暂无风险</p> : null}
        {risks.map((item) => (
          <ItemCard
            key={item.id}
            id={item.id}
            title={item.title}
            description={item.description}
            evidence={item.evidence}
            acceptanceStatus={item.acceptanceStatus}
            staleStatus={item.staleStatus}
            badges={[
              item.severity ? `严重度 ${item.severity}` : null,
              `状态 ${item.status}`,
            ]}
            kind="risks"
            pending={pendingId === item.id}
            onAccept={handleAccept}
            onReject={handleReject}
          />
        ))}
      </section>
    </main>
  );
}

interface ItemCardProps {
  id: string;
  title: string;
  description?: string | null;
  evidence: ItemEvidence[];
  acceptanceStatus: Acceptance;
  staleStatus: string;
  badges: (string | null | undefined)[];
  kind: ItemKind;
  pending: boolean;
  onAccept: (kind: ItemKind, itemId: string) => void;
  onReject: (kind: ItemKind, itemId: string) => void;
}

function ItemCard({ id, title, description, evidence, acceptanceStatus, staleStatus, badges, kind, pending, onAccept, onReject }: ItemCardProps) {
  const tone =
    acceptanceStatus === "ACCEPTED" ? "pill--success"
    : acceptanceStatus === "REJECTED" ? "pill--danger"
    : acceptanceStatus === "NEEDS_REVIEW" ? "pill--warn"
    : "pill--info";
  return (
    <article className="glass-panel glass-panel--compact stack item-card" data-item-id={id} data-status={acceptanceStatus}>
      <div className="toolbar">
        <strong>{title}</strong>
        <span className={`pill ${tone}`} data-acceptance={acceptanceStatus}>{acceptanceLabel(acceptanceStatus)}</span>
        {staleStatus && staleStatus !== "ACTIVE" ? <span className="pill pill--warn">{staleStatus}</span> : null}
        {badges.filter(Boolean).map((label) => (
          <span key={label} className="page-subtitle">{label}</span>
        ))}
      </div>
      {description ? <p>{description}</p> : null}
      {evidence.length > 0 ? (
        <ul className="evidence-list">
          {evidence.map((ev, idx) => (
            <li key={idx}>
              <span className="link">{ev.segmentId ?? "片段"}</span>
              {typeof ev.startMs === "number" && typeof ev.endMs === "number" ? (
                <span className="muted"> {formatMs(ev.startMs)} - {formatMs(ev.endMs)} </span>
              ) : null}
              {ev.evidenceTextSnapshot ? <span>{ev.evidenceTextSnapshot}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}
      <div className="toolbar">
        <button
          type="button"
          className="button button--primary"
          disabled={pending || acceptanceStatus === "ACCEPTED"}
          onClick={() => onAccept(kind, id)}
        >
          接受
        </button>
        <button
          type="button"
          className="button"
          disabled={pending || acceptanceStatus === "REJECTED"}
          onClick={() => onReject(kind, id)}
        >
          拒绝
        </button>
      </div>
    </article>
  );
}

function acceptanceLabel(status: Acceptance): string {
  switch (status) {
    case "DRAFT": return "AI 建议";
    case "ACCEPTED": return "已确认";
    case "REJECTED": return "已拒绝";
    case "NEEDS_REVIEW": return "待复核";
    default: return status;
  }
}

function formatMs(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}
