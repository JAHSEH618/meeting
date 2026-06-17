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
  READY: "pill--success",
  ARCHIVED: "pill--neutral",
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

  // Stats
  const stats = {
    total: meetings.length,
    processing: meetings.filter(m => m.status === 'PROCESSING').length,
    ready: meetings.filter(m => m.status === 'READY').length,
  };

  return (
    <div className="page">
      {/* Hero Section */}
      <header className="hero-section">
        <h1 className="page-title">会议智能平台</h1>
        <p className="hero-subtitle">AI 驱动的会议记录与分析系统</p>
        <div className="hero-actions">
          <Link className="button button--primary" to="/meetings/new">
            创建会议
          </Link>
          <Link className="button button--ghost" to="/documents">
            文档库
          </Link>
        </div>
      </header>

      {/* Stats Cards */}
      <div className="stats-grid">
        <div className="metric card">
          <div className="metric__value">{stats.total}</div>
          <div className="metric__label">总会议数</div>
        </div>
        <div className="metric card">
          <div className="metric__value">{stats.processing}</div>
          <div className="metric__label">处理中</div>
        </div>
        <div className="metric card">
          <div className="metric__value">{stats.ready}</div>
          <div className="metric__label">已完成</div>
        </div>
      </div>

      {/* Search & Table */}
      <section className="card stack">
        <div className="toolbar">
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
            <span>调整搜索条件，或点击右上「创建会议」开始。</span>
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
