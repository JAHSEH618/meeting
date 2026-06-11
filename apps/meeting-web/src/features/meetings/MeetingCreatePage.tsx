import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { createMeeting } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function MeetingCreatePage() {
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState("zh");
  const [participantsText, setParticipantsText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const participants = participantsText
        .split("\n")
        .map((line) => line.trim())
        .filter(Boolean)
        .map((displayName) => ({
          personId: "",
          displayName,
          role: "participant",
        }));
      const meeting = await createMeeting({
        title: title.trim(),
        language,
        participants,
      });
      navigate(`/meetings/${meeting.meetingId}`);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "会议创建失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">新建会议</h1>
          <p className="muted">创建后可在详情页启动处理任务。</p>
        </div>
      </div>
      <section className="card">
        <form className="form" onSubmit={onSubmit}>
          <div className="field">
            <label htmlFor="title">会议标题</label>
            <input
              id="title"
              type="text"
              name="title"
              autoComplete="off"
              required
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="language">语言</label>
            <select
              id="language"
              name="language"
              value={language}
              onChange={(event) => setLanguage(event.target.value)}
            >
              <option value="zh">中文</option>
              <option value="en">English</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="participants">参会人</label>
            <textarea
              id="participants"
              name="participants"
              rows={4}
              autoComplete="off"
              spellCheck={false}
              value={participantsText}
              onChange={(event) => setParticipantsText(event.target.value)}
              placeholder="每行一个姓名…"
            />
          </div>
          {error ? (
            <div className="error" role="alert" aria-live="polite">
              {error}
            </div>
          ) : null}
          <button className="primary" type="submit" disabled={submitting || !title.trim()}>
            {submitting ? "创建中…" : "创建会议"}
          </button>
        </form>
      </section>
    </main>
  );
}
