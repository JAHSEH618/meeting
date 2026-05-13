import { Link, useParams } from "react-router-dom";

export function ExportsPage() {
  const { meetingId = "" } = useParams();
  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">导出</h1>
          <p className="muted">{meetingId}</p>
        </div>
        <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
      </div>
      <section className="card">
        <p className="muted">导出任务将在后续切片接入。</p>
      </section>
    </main>
  );
}
