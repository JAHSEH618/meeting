import { Link } from "react-router-dom";
import { useAdminMeetingsQuery } from "@/features/meetings/queries";
import { formatDate } from "@/shared/utils/formatters";
import { formatError } from "@/shared/utils/format-error";

const STATUS_TONE: Record<string, string> = {
  CREATED: "pill--neutral",
  PROCESSING: "pill--info",
  SUCCEEDED: "pill--success",
  FAILED: "pill--danger",
  DELETED: "pill--danger",
};

export function MeetingsPage() {
  const { data, isPending, error } = useAdminMeetingsQuery();
  const meetings = data ?? [];
  const stats = {
    total: meetings.length,
    processing: meetings.filter((m) => m.status === "PROCESSING").length,
    ready: meetings.filter((m) => m.status === "SUCCEEDED").length,
  };

  return (
    <div className="workstation-page">
      <header className="workstation-hero">
        <div>
          <span className="workstation-hero__label">PYTHON AI WORKSTATION</span>
          <h1 className="workstation-hero__title">运营工作站</h1>
          <p className="workstation-hero__subtitle">
            面向 Python AI Worker 的会议处理入口，聚焦任务链路、声纹样本和结果复核。
          </p>
        </div>
        <div className="workstation-hero__actions">
          <Link className="button button--primary" to="/meetings/new">新建会议</Link>
          <Link className="button button--ghost" to="/enrollment">声纹录入</Link>
        </div>
      </header>

      <section className="workstation-stats" aria-label="工作站概览">
        <div className="workstation-stat">
          <strong>{stats.total}</strong>
          <span>会议总数</span>
        </div>
        <div className="workstation-stat">
          <strong>{stats.processing}</strong>
          <span>处理中</span>
        </div>
        <div className="workstation-stat">
          <strong>{stats.ready}</strong>
          <span>已完成</span>
        </div>
      </section>

      <section className="workstation-modules grid-12" aria-label="Python 工作站模块">
        <article className="workstation-module workstation-module--wide span-6">
          <div>
            <p className="workstation-module__eyebrow">01 / FLOW</p>
            <h2>处理链路</h2>
            <p>建会议、上传音频、启动 worker、确认说话人、生成纪要和导出结果，按处理阶段组织入口。</p>
          </div>
          <div className="workstation-module__rail">
            <span>建会</span>
            <span>上传</span>
            <span>Worker</span>
            <span>复核</span>
          </div>
        </article>

        <article className="workstation-module workstation-module--wide span-6">
          <div>
            <p className="workstation-module__eyebrow">02 / VOICE</p>
            <h2>声纹治理</h2>
            <p>人员、录入会话和声纹档案保持同一工作区，减少从会议处理跳到样本管理的割裂感。</p>
          </div>
          <div className="workstation-module__meta">
            <span>人员</span>
            <span>录入</span>
            <span>档案</span>
          </div>
        </article>

        <article className="workstation-module span-4">
          <p className="workstation-module__eyebrow">03 / REVIEW</p>
          <h2>质量复核</h2>
          <p>把转录、说话人确认和纪要结果放在任务上下文里看，减少孤立功能卡。</p>
        </article>

        <article className="workstation-module span-4">
          <p className="workstation-module__eyebrow">04 / INDEX</p>
          <h2>会议索引</h2>
          <p>近期会议仍用表格承载，状态、语言、创建时间保持稳定扫描节奏。</p>
        </article>

        <article className="workstation-module span-4">
          <p className="workstation-module__eyebrow">05 / LOCAL</p>
          <h2>本地工作台</h2>
          <p>玻璃背景只做层级和聚焦，不额外放品牌块或装饰标识。</p>
        </article>
      </section>

      <section className="glass-table-panel stack">
        <strong className="section-title">近期会议</strong>
        {isPending ? (
          <p className="page-subtitle" aria-live="polite">加载中…</p>
        ) : null}
        {error ? (
          <div className="banner banner--danger" role="alert">
            <strong className="banner__title">会议列表加载失败</strong>
            <span className="banner__body">
              {formatError(error)}
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
                  <td><span className={`pill ${STATUS_TONE[m.status] ?? "pill--neutral"}`}>{m.status}</span></td>
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
