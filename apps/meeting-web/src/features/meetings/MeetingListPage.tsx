import { useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useMeetingsQuery } from "./queries";
import { formatDate } from "@shared/utils/formatters";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";
import type { Meeting } from "@shared/api/types";

const SECURITY_LEVELS = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"] as const;

const SECURITY_TONE: Record<string, string> = {
  PUBLIC: "pill--neutral",
  INTERNAL: "pill--info",
  CONFIDENTIAL: "pill--warn",
  SECRET: "pill--danger",
};

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
  const security = params.get("securityLevel") ?? "ALL";

  const { data, isPending, error } = useMeetingsQuery();
  const meetings = data?.items ?? [];

  const filtered = useMemo(() => {
    return meetings.filter((m) => {
      const titleMatch = m.title.toLowerCase().includes(keyword.trim().toLowerCase());
      const securityMatch = security === "ALL" || m.securityLevel === security;
      return titleMatch && securityMatch;
    });
  }, [meetings, keyword, security]);

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (!v || v === "ALL") merged.delete(k);
      else merged.set(k, v);
    }
    setParams(merged, { replace: true });
  }

  const errorMsg = error
    ? ((error as ApiClientError).code
        ? getUserMessage((error as ApiClientError).code!)
        : "会议列表加载失败")
    : null;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">会议</h1>
          <p className="page-subtitle">查看会议处理状态并进入详情。</p>
        </div>
        <div className="page-actions">
          <Link className="button button--primary" to="/meetings/new">+ 新建会议</Link>
        </div>
      </header>

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
          <div className="field" style={{ minWidth: 200 }}>
            <label className="field__label" htmlFor="meeting-security">安全等级</label>
            <select
              id="meeting-security"
              name="securityLevel"
              value={security}
              onChange={(e) => update({ securityLevel: e.target.value })}
            >
              <option value="ALL">全部安全等级</option>
              {SECURITY_LEVELS.map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
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
            <span>调整搜索条件，或点击右上「+ 新建会议」开始。</span>
          </div>
        ) : null}

        {filtered.length > 0 ? (
          <table className="data-table">
            <thead>
              <tr>
                <th>标题</th>
                <th>状态</th>
                <th>安全等级</th>
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
                  <td>
                    <span className={`pill ${SECURITY_TONE[meeting.securityLevel] ?? "pill--neutral"}`}>
                      {meeting.securityLevel}
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
