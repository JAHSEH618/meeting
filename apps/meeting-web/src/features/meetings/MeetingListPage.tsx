import { useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useMeetingsQuery } from "./queries";
import { formatDate, formatLanguage, formatMeetingStatus } from "@shared/utils/formatters";
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
  const statusFilter = params.get("status") ?? "";

  const { data, isPending, error } = useMeetingsQuery();
  const meetings = data?.items ?? [];

  const filtered = useMemo(() => {
    return meetings.filter((m) => {
      const matchesKeyword = m.title.toLowerCase().includes(keyword.trim().toLowerCase());
      const matchesStatus = !statusFilter || m.status === statusFilter;
      return matchesKeyword && matchesStatus;
    });
  }, [meetings, keyword, statusFilter]);

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
  const latestMeeting = [...meetings].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  )[0];
  const pendingMeetings = meetings
    .filter((meeting) => meeting.status === "CREATED")
    .sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());
  const nextPendingMeeting = pendingMeetings[0];
  const currentStatusLabel = statusFilter ? formatMeetingStatus(statusFilter) : "";

  return (
    <div className="page page--hero">
      <header className="page-hero">
        <div>
          <span className="page-hero__label">智能工作台</span>
          <h1 className="page-hero__title">会议智能平台</h1>
          <p className="page-hero__subtitle">实时转录、结构化纪要、知识问答与声纹档案，集中在一个本地工作台中完成。</p>
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
        <button
          type="button"
          className="stat-card stat-card--button"
          aria-label={`全部会议，总会议数 ${stats.total} 个`}
          aria-pressed={!statusFilter}
          data-active={!statusFilter}
          onClick={() => update({ status: "" })}
        >
          <div className="stat-card__value">{stats.total}</div>
          <div className="stat-card__label">总会议数</div>
        </button>
        <button
          type="button"
          className="stat-card stat-card--button"
          aria-label={`处理中会议 ${stats.processing} 个`}
          aria-pressed={statusFilter === "PROCESSING"}
          data-active={statusFilter === "PROCESSING"}
          onClick={() => update({ status: "PROCESSING" })}
        >
          <div className="stat-card__value">{stats.processing}</div>
          <div className="stat-card__label">处理中</div>
        </button>
        <button
          type="button"
          className="stat-card stat-card--button"
          aria-label={`已完成会议 ${stats.ready} 个`}
          aria-pressed={statusFilter === "SUCCEEDED"}
          data-active={statusFilter === "SUCCEEDED"}
          onClick={() => update({ status: "SUCCEEDED" })}
        >
          <div className="stat-card__value">{stats.ready}</div>
          <div className="stat-card__label">已完成</div>
        </button>
      </section>

      <section className="meeting-modules grid-12" aria-label="会议工作流模块">
        <article className="module-card module-card--wide span-6">
          <div>
            <p className="module-card__eyebrow">会议动态</p>
            <h2>最近会议</h2>
            {latestMeeting ? (
              <p>
                {latestMeeting.title} · {formatMeetingStatus(latestMeeting.status)} · {formatDate(latestMeeting.createdAt)}
              </p>
            ) : isPending ? (
              <p>正在加载最近会议…</p>
            ) : (
              <p>还没有会议记录，可先创建一场会议。</p>
            )}
          </div>
          <div className="module-card__actions">
            {latestMeeting ? (
              <Link className="button button--compact" to={`/meetings/${latestMeeting.meetingId}`}>
                打开最近会议
              </Link>
            ) : isPending ? (
              <Link className="button button--compact" to="/meetings">
                打开最近会议
              </Link>
            ) : (
              <Link className="button button--compact" to="/meetings/new">
                新建会议
              </Link>
            )}
          </div>
        </article>

        <article className="module-card module-card--wide span-6">
          <div>
            <p className="module-card__eyebrow">待办</p>
            <h2>待启动处理</h2>
            {nextPendingMeeting ? (
              <p>
                {pendingMeetings.length} 场会议还未启动音频处理，下一场是 {nextPendingMeeting.title}。
              </p>
            ) : (
              <p>当前没有待启动处理的会议。</p>
            )}
          </div>
          <div className="module-card__actions">
            {nextPendingMeeting ? (
              <Link className="button button--compact" to={`/meetings/${nextPendingMeeting.meetingId}/audio`}>
                进入处理
              </Link>
            ) : (
              <Link className="button button--compact" to="/meetings/new">
                新建会议
              </Link>
            )}
          </div>
        </article>

        <article className="module-card span-4">
          <p className="module-card__eyebrow">入口</p>
          <h2>快捷入口</h2>
          <div className="module-card__link-list" aria-label="快捷入口">
            <Link to="/meetings/new">新建会议</Link>
            <Link to="/speaker-profiles">声纹档案</Link>
            <Link to="/documents">文档库</Link>
          </div>
        </article>

        <article className="module-card span-4">
          <p className="module-card__eyebrow">检索</p>
          <h2>会议索引</h2>
          <p>{filtered.length} 条记录符合当前条件，列表可继续按标题和状态缩小范围。</p>
        </article>

        <article className="module-card span-4">
          <p className="module-card__eyebrow">知识</p>
          <h2>知识内容</h2>
          <div className="module-card__link-list" aria-label="知识入口">
            <Link to="/rag">知识问答</Link>
            <Link to="/documents">文档库</Link>
            <Link to="/speaker-profiles">声纹档案</Link>
          </div>
        </article>
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
          {statusFilter ? (
            <div className="filter-chip" aria-live="polite">
              <span>当前筛选：{currentStatusLabel}</span>
              <button
                type="button"
                className="button button--subtle button--compact"
                onClick={() => update({ status: "" })}
              >
                清除筛选
              </button>
            </div>
          ) : null}
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
                      {formatMeetingStatus(meeting.status)}
                    </span>
                  </td>
                  <td>{formatLanguage(meeting.language)}</td>
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
