import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { createProcessingTask, getMeeting } from "@shared/api/client";
import type { Meeting } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function MeetingDetailPage() {
  const { meetingId } = useParams();
  const [meeting, setMeeting] = useState<Meeting | null>(null);
  const [audioFileId, setAudioFileId] = useState("audio_fixture_01");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!meetingId) return;
    getMeeting(meetingId)
      .then(setMeeting)
      .catch((cause) => {
        const apiError = cause as ApiClientError;
        setError(apiError.code ? getUserMessage(apiError.code) : "会议详情加载失败");
      })
      .finally(() => setLoading(false));
  }, [meetingId]);

  async function startTask() {
    if (!meetingId) return;
    setSubmitting(true);
    setError(null);
    try {
      const task = await createProcessingTask(meetingId, audioFileId.trim() || "audio_fixture_01");
      navigate(`/meetings/${meetingId}/tasks/${task.taskId}`);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "处理任务创建失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page">
      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}
      {meeting ? (
        <div className="stack">
          <div className="page-header">
            <div>
              <h1 className="page-title">{meeting.title}</h1>
              <p className="muted">{meeting.meetingId}</p>
            </div>
            <Link className="button" to="/meetings">返回列表</Link>
          </div>

          <section className="grid">
            <div className="card">
              <div className="muted">状态</div>
              <h2>{meeting.status}</h2>
            </div>
            <div className="card">
              <div className="muted">安全等级</div>
              <h2>{meeting.securityLevel}</h2>
            </div>
            <div className="card">
              <div className="muted">版本</div>
              <h2>转录 {meeting.transcriptVersion} / 纪要 {meeting.minutesVersion}</h2>
            </div>
          </section>

          <section className="card stack">
            <h2>处理任务</h2>
            <div className="field" style={{ maxWidth: 420 }}>
              <label htmlFor="audioFileId">音频文件 ID</label>
              <input id="audioFileId" value={audioFileId} onChange={(event) => setAudioFileId(event.target.value)} />
            </div>
            <button className="primary" type="button" onClick={startTask} disabled={submitting}>
              {submitting ? "启动中" : "启动 MEETING_FULL_PIPELINE"}
            </button>
          </section>
        </div>
      ) : null}
    </main>
  );
}
