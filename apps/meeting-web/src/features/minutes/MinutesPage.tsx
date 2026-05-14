import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getMeeting, getMinutes, regenerateMinutes } from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import type { Meeting, MinutesData, MinutesSection } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import { SecurityLevelBlockedNotice } from "@shared/components/SecurityLevelBlockedNotice";

export function MinutesPage() {
  const { meetingId = "" } = useParams();
  const [meeting, setMeeting] = useState<Meeting | null>(null);
  const [minutes, setMinutes] = useState<MinutesData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [blocked, setBlocked] = useState(false);
  const [regenerating, setRegenerating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setBlocked(false);
    try {
      const [meetingResult, minutesResult] = await Promise.allSettled([
        getMeeting(meetingId),
        getMinutes(meetingId),
      ]);
      if (meetingResult.status === "fulfilled") setMeeting(meetingResult.value);
      if (minutesResult.status === "fulfilled") {
        setMinutes(minutesResult.value);
      } else {
        const apiError = minutesResult.reason as ApiClientError;
        if (apiError.status === 404) {
          setMinutes(null);
        } else if (apiError.code === "SECURITY_LEVEL_BLOCKED") {
          setBlocked(true);
          setMinutes(null);
        } else {
          setError(apiError.code ? getUserMessage(apiError.code) : "纪要加载失败");
        }
      }
    } finally {
      setLoading(false);
    }
  }, [meetingId]);

  useEffect(() => {
    if (!meetingId) return;
    void load();
  }, [meetingId, load]);

  const handleRegenerate = async () => {
    if (!meeting) return;
    setRegenerating(true);
    setError(null);
    setBlocked(false);
    try {
      const next = await regenerateMinutes(
        meetingId,
        meeting.transcriptVersion,
        minutes?.minutesVersion ?? 0,
      );
      setMinutes(next);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      if (apiError.code === "SECURITY_LEVEL_BLOCKED") {
        setBlocked(true);
      } else if (apiError.code === "VERSION_CONFLICT") {
        await load();
        setError("内容已被更新，请刷新后重试");
      } else {
        setError(apiError.code ? getUserMessage(apiError.code) : "重生成失败");
      }
    } finally {
      setRegenerating(false);
    }
  };

  const sections = minutes?.sections ?? [];
  const isStale = minutes?.staleStatus && minutes.staleStatus !== "ACTIVE" && minutes.staleStatus !== "CURRENT";

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">会议纪要</h1>
          <p className="muted">{meetingId}</p>
        </div>
        <div className="toolbar">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/transcript`}>查看转录</Link>
          <button
            type="button"
            className="button primary"
            onClick={() => void handleRegenerate()}
            disabled={!meeting || regenerating}
          >
            {regenerating ? "重生成中" : "重新生成纪要"}
          </button>
        </div>
      </div>

      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}

      {blocked ? (
        <SecurityLevelBlockedNotice
          securityLevel={meeting?.securityLevel}
          blockedCapability="MINUTES_SUMMARY"
        />
      ) : null}

      {isStale ? (
        <section className="card stack" role="status" aria-live="polite">
          <strong>当前纪要标记为 {minutes?.staleStatus}</strong>
          <span className="muted">转录已被编辑，建议重新生成纪要以同步最新内容。</span>
        </section>
      ) : null}

      {!loading && !minutes && !blocked ? (
        <section className="card stack">
          <strong>暂无纪要</strong>
          <span className="muted">完成转录后点击"重新生成纪要"</span>
        </section>
      ) : null}

      {minutes ? (
        <section className="card stack">
          <div className="toolbar">
            <strong>纪要</strong>
            <span className="badge">v{minutes.minutesVersion}</span>
            <span className="badge">{minutes.staleStatus}</span>
          </div>

          {minutes.markdown ? (
            <pre className="markdown-preview" aria-label="纪要 markdown">{minutes.markdown}</pre>
          ) : null}

          <div className="stack">
            {sections.map((section, idx) => (
              <MinutesSectionView key={`${section.type}-${idx}`} section={section} meetingId={meetingId} />
            ))}
          </div>
        </section>
      ) : null}
    </main>
  );
}

function MinutesSectionView({ section, meetingId }: { section: MinutesSection; meetingId: string }) {
  const items = section.items ?? [];
  return (
    <section className="card stack">
      <div className="toolbar">
        <strong>{section.title}</strong>
        <span className="muted">{section.type}</span>
      </div>
      {items.length === 0 ? <p className="muted">本节暂无条目</p> : null}
      {items.map((item, idx) => (
        <article key={idx} className="stack">
          <p>{item.text}</p>
          {item.evidence && item.evidence.length > 0 ? (
            <ul className="evidence-list">
              {item.evidence.map((ev, evIdx) => (
                <li key={evIdx}>
                  <Link to={`/meetings/${meetingId}/transcript`} className="link">
                    {ev.segmentId ?? "片段"}
                  </Link>
                  {typeof ev.startMs === "number" && typeof ev.endMs === "number" ? (
                    <span className="muted"> {formatMs(ev.startMs)} - {formatMs(ev.endMs)} </span>
                  ) : null}
                  {ev.evidenceTextSnapshot ? <span>{ev.evidenceTextSnapshot}</span> : null}
                </li>
              ))}
            </ul>
          ) : null}
        </article>
      ))}
    </section>
  );
}

function formatMs(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}
