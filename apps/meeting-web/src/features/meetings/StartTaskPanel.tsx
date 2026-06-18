import { Link } from "react-router-dom";

export function StartTaskPanel({ meetingId }: { meetingId: string }) {
  return (
    <section className="glass-panel glass-panel--compact stack">
      <div>
        <strong>处理任务</strong>
        <p className="page-subtitle">
          选择会议音频后，系统会完成上传并自动启动处理任务。
        </p>
      </div>
      <div className="banner banner--info">
        <strong className="banner__title">无需手动填写文件编号</strong>
        <span className="banner__body">上传完成后会自动进入处理进度页。</span>
      </div>
      <Link className="button button--primary" to={`/meetings/${meetingId}/audio`}>
        选择音频并处理
      </Link>
    </section>
  );
}
