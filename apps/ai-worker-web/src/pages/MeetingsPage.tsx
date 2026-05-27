import { Link } from "react-router-dom";
import { useAdminMeetingsQuery } from "@/features/meetings/queries";
import { formatDate } from "@/shared/utils/formatters";
import { ApiError } from "@/shared/api/client";

const SECURITY_TONE: Record<string, string> = {
  PUBLIC: "pill--neutral",
  INTERNAL: "pill--info",
  CONFIDENTIAL: "pill--warn",
  SECRET: "pill--danger",
};

export function MeetingsPage() {
  const { data, isPending, error } = useAdminMeetingsQuery();
  const meetings = data ?? [];

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">运营工作站</h1>
          <p className="page-subtitle">选择会议进入工作站，或新建一个流程。</p>
        </div>
      </header>

      <section className="grid">
        <Link
          className="card stack"
          to="/meetings/new"
          style={{ textDecoration: "none", color: "inherit" }}
        >
          <strong>新建会议工作流</strong>
          <span className="page-subtitle">
            建会议 · 上传录音 · 术语 · 文档 · 启动 worker · 确认说话人 · 生成纪要 · 导出
          </span>
        </Link>
        <Link
          className="card stack"
          to="/enrollment"
          style={{ textDecoration: "none", color: "inherit" }}
        >
          <strong>声纹录入</strong>
          <span className="page-subtitle">为人员录入声纹样本，建立档案。</span>
        </Link>
      </section>

      <section className="card stack">
        <strong>近期会议</strong>
        {isPending ? (
          <p className="page-subtitle" aria-live="polite">加载中…</p>
        ) : null}
        {error ? (
          <div className="banner banner--danger" role="alert">
            <strong className="banner__title">会议列表加载失败</strong>
            <span className="banner__body">
              {error instanceof ApiError
                ? `${error.error.code}: ${error.error.message}`
                : "稍后重试"}
            </span>
          </div>
        ) : null}
        {!isPending && !error && meetings.length === 0 ? (
          <div className="empty-state">
            <strong>暂无会议</strong>
            <span>点击「新建会议工作流」开始。</span>
          </div>
        ) : null}
        {meetings.length > 0 ? (
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
              {meetings.map((m) => (
                <tr key={m.meetingId}>
                  <td>
                    <Link to={`/meetings/${m.meetingId}`}>{m.title}</Link>
                  </td>
                  <td><span className="pill pill--info">{m.status}</span></td>
                  <td>
                    <span className={`pill ${SECURITY_TONE[m.securityLevel] ?? "pill--neutral"}`}>
                      {m.securityLevel}
                    </span>
                  </td>
                  <td>{m.language}</td>
                  <td>{formatDate(m.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </div>
  );
}
