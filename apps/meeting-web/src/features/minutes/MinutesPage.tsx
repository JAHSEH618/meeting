import { Link, useParams } from "react-router-dom";
import { useMinutesQuery, useMeetingForMinutes, useRegenerateMinutes } from "./queries";
import type { ApiClientError } from "@shared/api/client";
import type { MinutesSection } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import { SafeMarkdown } from "@shared/components/SafeMarkdown";
import { MeetingTabBar } from "@features/meetings/MeetingTabBar";
import { formatMs } from "@shared/utils/formatters";

export function MinutesPage() {
  const { meetingId = "" } = useParams();
  const { data: meeting } = useMeetingForMinutes(meetingId);
  const { data: minutes, error, isPending } = useMinutesQuery(meetingId);
  const regen = useRegenerateMinutes(meetingId);

  const loadErr = error as ApiClientError | null;
  const regenErr = regen.error as ApiClientError | null;

  const notFound = loadErr?.status === 404;
  const otherLoadErrMsg =
    loadErr && !notFound
      ? (loadErr.code ? getUserMessage(loadErr.code) : "纪要加载失败")
      : null;
  const regenErrMsg =
    regenErr
      ? (regenErr.code === "VERSION_CONFLICT"
          ? "内容已被更新，请刷新后重试"
          : (regenErr.code ? getUserMessage(regenErr.code) : "重新生成失败"))
      : null;

  const sections = minutes?.sections ?? [];
  const isStale =
    minutes?.staleStatus && minutes.staleStatus !== "ACTIVE" && minutes.staleStatus !== "CURRENT";

  const onRegenerate = () => {
    if (!meeting) return;
    regen.mutate({
      transcriptVersion: meeting.transcriptVersion,
      minutesVersion: minutes?.minutesVersion ?? 0,
    });
  };

  return (
    <main className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">MINUTES</span>
          <h1 className="page-hero__title">会议纪要</h1>
          <p className="page-hero__subtitle"><span translate="no">{meetingId}</span></p>
        </div>
        <div className="page-hero__actions">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/transcript`}>查看转录</Link>
          <button
            type="button"
            className="button button--primary"
            onClick={onRegenerate}
            disabled={!meeting || regen.isPending}
          >
            {regen.isPending ? "重生成中…" : "重新生成纪要"}
          </button>
        </div>
      </header>

      <MeetingTabBar />

      {isPending ? <p className="page-subtitle" aria-live="polite">加载中…</p> : null}
      {otherLoadErrMsg ? <div className="error" role="alert">{otherLoadErrMsg}</div> : null}
      {regenErrMsg ? <div className="error" role="alert">{regenErrMsg}</div> : null}

      {isStale ? (
        <section className="banner banner--warn" role="status" aria-live="polite">
          <strong className="banner__title">当前纪要标记为 {minutes?.staleStatus}</strong>
          <span className="banner__body">转录已被编辑，建议重新生成纪要以同步最新内容。</span>
        </section>
      ) : null}

      {!isPending && !minutes ? (
        <section className="empty-state">
          <strong>暂无纪要</strong>
          <span>完成转录后点击"重新生成纪要"。</span>
        </section>
      ) : null}

      {minutes ? (
        <section className="glass-panel stack">
          <div className="toolbar">
            <strong>纪要</strong>
            <span className="pill pill--info">v{minutes.minutesVersion}</span>
            <span className="pill pill--neutral">{minutes.staleStatus}</span>
          </div>

          {minutes.markdown ? (
            <SafeMarkdown
              source={minutes.markdown}
              className="markdown-preview"
              ariaLabel="纪要 markdown"
            />
          ) : null}

          <div className="stack">
            {sections.map((section, idx) => (
              <MinutesSectionView
                key={`${section.type}-${idx}`}
                section={section}
                meetingId={meetingId}
              />
            ))}
          </div>
        </section>
      ) : null}
    </main>
  );
}

function MinutesSectionView({
  section,
  meetingId,
}: {
  section: MinutesSection;
  meetingId: string;
}) {
  const items = section.items ?? [];
  return (
    <section className="glass-panel glass-panel--compact stack">
      <div className="toolbar">
        <strong>{section.title}</strong>
        <span className="page-subtitle">{section.type}</span>
      </div>
      {items.length === 0 ? <p className="page-subtitle">本节暂无条目</p> : null}
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
                    <span className="page-subtitle"> {formatMs(ev.startMs)} - {formatMs(ev.endMs)} </span>
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
