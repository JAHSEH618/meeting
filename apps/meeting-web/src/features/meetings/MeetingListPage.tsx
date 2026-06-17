import { useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useMeetingsQuery } from "./queries";
import { formatDate } from "@shared/utils/formatters";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";
import type { Meeting } from "@shared/api/types";

const STATUS_TONE: Record<string, string> = {
  CREATED: "pill--neutral",
  PROCESSING: "pill--info",
  SUCCEEDED: "pill--success",
  FAILED: "pill--danger",
  DELETED: "pill--danger",
};

export function MeetingListPage() {
  const [params, setParams] = useSearchParams();
  const keyword = params.get("q") ?? "";

  const { data, isPending, error } = useMeetingsQuery();
  const meetings = data?.items ?? [];

  const filtered = useMemo(() => {
    return meetings.filter((m) => {
      return m.title.toLowerCase().includes(keyword.trim().toLowerCase());
    });
  }, [meetings, keyword]);

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (!v) merged.delete(k);
      else merged.set(k, v);
    }
    setParams(merged, { replace: true });
  }

  const errorMsg = error
    ? ((error as ApiClientError).code
        ? getUserMessage((error as ApiClientError).code!)
        : "会议列表加载失败")
    : null;

  const stats = {
    total: meetings.length,
    processing: meetings.filter(m => m.status === 'PROCESSING').length,
    ready: meetings.filter(m => m.status === 'SUCCEEDED').length,
  };

  return (
    <div className="page page--hero">
      <header className="page-hero">
        <div>
          <span className="page-hero__label">POWERED BY AI</span>
          <h1 className="page-hero__title">会议智能平台</h1>
          <p className="page-hero__subtitle">实时转录、结构化纪要、知识问答与合规留痕，集中在一个本地工作台中完成。</p>
        </div>
        <div className="page-hero__actions">
          <Link className="button button--primary" to="/meetings/new">
            创建会议
          </Link>
          <Link className="button button--ghost" to="/documents">
            文档库
          </Link>
        </div>
      </header>

      <section className="stats-grid" aria-label="会议概览">
        <div className="stat-card">
          <div className="stat-card__value">{stats.total}</div>
          <div className="stat-card__label">总会议数</div>
        </div>
        <div className="stat-card">
          <div className="stat-card__value">{stats.processing}</div>
          <div className="stat-card__label">处理中</div>
        </div>
        <div className="stat-card">
          <div className="stat-card__value">{stats.ready}</div>
          <div className="stat-card__label">已完成</div>
        </div>
      </section>

      <section className="glass-panel glass-panel--hero">
        <h2 className="glass-panel__title">实时 AI 会议助手</h2>
        <p className="glass-panel__body">自动沉淀会议内容、追踪处理状态，并把可检索的组织知识留在本地系统内。</p>
      </section>

      <section className="glass-panel glass-panel--table stack">
        <div className="meeting-list-toolbar toolbar">
          <div className="field" style={{ flex: 1, minWidth: 220 }}>
            <label className="field__label" htmlFor="meeting-search">搜索会议</label>
            <input
              id="meeting-search"
              type="search"
              name="q"
              autoComplete="off"
              placeholder="按标题搜索…"
              value={keyword}
              onChange={(e) => update({ q: e.target.value })}
            />
          </div>
        </div>

        {isPending ? <p className="page-subtitle" aria-live="polite">加载中…</p> : null}
        {errorMsg ? (
          <div className="banner banner--danger" role="alert">
            <strong className="banner__title">列表加载失败</strong>
            <span className="banner__body">{errorMsg}</span>
          </div>
        ) : null}
        {!isPending && !error && filtered.length === 0 ? (
          <div className="empty-state">
            <strong>暂无符合条件的会议</strong>
            <span>调整搜索条件，或创建一场新会议。</span>
          </div>
        ) : null}

        {filtered.length > 0 ? (
          <table className="data-table">
            <thead>
              <tr>
                <th>标题</th>
                <th>状态</th>
                <th>语言</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((meeting: Meeting) => (
                <tr key={meeting.meetingId}>
                  <td>
                    <Link to={`/meetings/${meeting.meetingId}`}>{meeting.title}</Link>
                  </td>
                  <td>
                    <span className={`pill ${STATUS_TONE[meeting.status] ?? "pill--neutral"}`}>
                      {meeting.status}
                    </span>
                  </td>
                  <td>{meeting.language}</td>
                  <td>{formatDate(meeting.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </div>
  );
}
