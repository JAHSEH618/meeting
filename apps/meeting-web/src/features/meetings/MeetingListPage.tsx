import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { listMeetings } from "@shared/api/client";
import type { Meeting } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function MeetingListPage() {
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState("");
  const [securityLevel, setSecurityLevel] = useState("ALL");

  useEffect(() => {
    listMeetings()
      .then((page) => setMeetings(page.items ?? []))
      .catch((cause) => {
        const apiError = cause as ApiClientError;
        setError(apiError.code ? getUserMessage(apiError.code) : "会议列表加载失败");
      })
      .finally(() => setLoading(false));
  }, []);

  const filtered = useMemo(() => {
    return meetings.filter((meeting) => {
      const titleMatched = meeting.title.toLowerCase().includes(keyword.trim().toLowerCase());
      const levelMatched = securityLevel === "ALL" || meeting.securityLevel === securityLevel;
      return titleMatched && levelMatched;
    });
  }, [meetings, keyword, securityLevel]);

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">会议</h1>
          <p className="muted">查看会议处理状态并进入详情。</p>
        </div>
        <Link className="button primary" to="/meetings/new">新建会议</Link>
      </div>

      <div className="card stack">
        <div className="toolbar">
          <input aria-label="搜索会议" placeholder="搜索标题" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          <select aria-label="安全等级" value={securityLevel} onChange={(event) => setSecurityLevel(event.target.value)}>
            <option value="ALL">全部安全等级</option>
            <option value="PUBLIC">PUBLIC</option>
            <option value="INTERNAL">INTERNAL</option>
            <option value="CONFIDENTIAL">CONFIDENTIAL</option>
            <option value="SECRET">SECRET</option>
          </select>
        </div>

        {loading ? <p className="muted">加载中</p> : null}
        {error ? <div className="error" role="alert">{error}</div> : null}
        {!loading && !error && filtered.length === 0 ? <p className="muted">暂无会议</p> : null}

        {filtered.length > 0 ? (
          <table className="table">
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
              {filtered.map((meeting) => (
                <tr key={meeting.meetingId}>
                  <td><Link to={`/meetings/${meeting.meetingId}`}>{meeting.title}</Link></td>
                  <td><span className="badge">{meeting.status}</span></td>
                  <td>{meeting.securityLevel}</td>
                  <td>{meeting.language}</td>
                  <td>{new Date(meeting.createdAt).toLocaleString("zh-CN")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </div>
    </main>
  );
}
