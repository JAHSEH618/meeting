import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { subscribeEventStream, type EventStreamSubscription } from "@/shared/api/client";
import {
  confirmSpeaker,
  createExport,
  getMeetingAggregate,
  pollExport,
  processingTaskEventsUrl,
  rejectSpeaker,
  searchPersons,
  updateMeeting,
} from "@/shared/api/endpoints";
import type {
  ExportJobDTO,
  MeetingAggregateDTO,
  MeetingParticipantDTO,
  MeetingSpeakerDTO,
  PersonDTO,
  SpeakerCandidateDTO,
  ProcessingTaskStepDTO,
  ProcessingTaskStatus,
  TaskEventDTO,
} from "@/shared/api/types";
import { useDebouncedSearch } from "@/shared/hooks/useDebouncedSearch";
import { SafeMarkdown } from "@/shared/markdown/SafeMarkdown";
import { formatError } from "@/shared/utils/format-error";

const STEPS = [
  "AUDIO_PREPROCESS",
  "ASR",
  "ALIGNMENT",
  "DIARIZATION",
  "SPEAKER_EMBEDDING",
  "SPEAKER_MATCHING",
  "TRANSCRIPT_MERGE",
  "RAG_INDEXING",
  "SUMMARY",
  "EXTRACTION",
] as const;

const TERMINAL_STATUSES: ProcessingTaskStatus[] = ["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"];
// Upper bound on how long the export button waits before handing off to
// a background-completion hint (renders can legitimately take minutes).
const EXPORT_WAIT_MS = 5 * 60 * 1000;
const DEFAULT_PARTICIPANT_ROLE = "PARTICIPANT";

// Aggregate sub-resources the BFF may report as failed-upstream.
const DEGRADED_LABELS: Record<string, string> = {
  latestTask: "处理任务",
  speakers: "说话人结果",
  minutes: "会议纪要",
};

export function MeetingDetailPage() {
  const { meetingId = "" } = useParams<{ meetingId: string }>();
  const [aggregate, setAggregate] = useState<MeetingAggregateDTO | null>(null);
  const [steps, setSteps] = useState<Record<string, ProcessingTaskStepDTO>>({});
  const [exportJob, setExportJob] = useState<ExportJobDTO | null>(null);
  const [busyExport, setBusyExport] = useState(false);
  const exportAbortRef = useRef<AbortController | null>(null);
  const [confirmingSpeaker, setConfirmingSpeaker] = useState<string | null>(null);
  const [pendingRejectSpeaker, setPendingRejectSpeaker] = useState<MeetingSpeakerDTO | null>(null);
  const [rejectingSpeaker, setRejectingSpeaker] = useState<string | null>(null);
  const [addingParticipant, setAddingParticipant] = useState<string | null>(null);
  const [rejectError, setRejectError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const task = aggregate?.latestTask ?? null;
  const meeting = aggregate?.meeting ?? null;
  const participants = meeting?.participants ?? [];
  const isTerminal = task ? TERMINAL_STATUSES.includes(task.status) : false;
  const terminalContentVisible = isTerminal || !!aggregate?.minutes || !!aggregate?.speakers?.speakers.length;
  const personFetcher = useCallback((q: string, signal: AbortSignal) => searchPersons(q, { signal }), []);
  const personSearch = useDebouncedSearch<PersonDTO>(personFetcher);
  const personResults = personSearch.results ?? [];

  useEffect(() => {
    if (!meetingId) return;
    let cancelled = false;
    let eventStream: EventStreamSubscription | null = null;
    let pollTimer: ReturnType<typeof setInterval> | null = null;

    const load = async () => {
      try {
        const data = await getMeetingAggregate(meetingId);
        if (cancelled) return;
        setAggregate(data);
        seedSteps(data.latestTask?.steps);
        const latest = data.latestTask;
        if (latest && TERMINAL_STATUSES.includes(latest.status)) {
          // Pipeline finished — nothing changes on its own anymore. Stop the
          // fallback poll (it used to run every 5s forever, fanning out to
          // several upstream Java calls per tick) and the event stream.
          eventStream?.close();
          eventStream = null;
          if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
          }
          return;
        }
        if (latest?.taskId && !eventStream) {
          eventStream = openTaskEvents(latest.taskId, () => {
            if (!pollTimer) {
              pollTimer = setInterval(() => void load(), 5000);
            }
          });
        }
      } catch (e) {
        if (!cancelled) setError(formatError(e));
      }
    };

    void load();
    return () => {
      cancelled = true;
      eventStream?.close();
      if (pollTimer) clearInterval(pollTimer);
    };
  }, [meetingId]);

  const sortedSteps = useMemo(
    () => STEPS.map((stepName) => steps[stepName] ?? { stepName, status: "PENDING", progress: 0 }),
    [steps],
  );

  const seedSteps = (incoming?: ProcessingTaskStepDTO[]) => {
    if (!incoming?.length) return;
    setSteps((current) => ({ ...current, ...Object.fromEntries(incoming.map((step) => [step.stepName, normalizeStep(step)])) }));
  };

  const openTaskEvents = (taskId: string, onFallback: () => void) => {
    const handleEvent = (payload: TaskEventDTO) => {
      try {
        if (payload.steps?.length) {
          seedSteps(payload.steps);
        } else if (payload.stepName) {
          seedSteps([{
            stepName: payload.stepName,
            status: payload.status ?? "RUNNING",
            progress: payload.progress ?? 0,
            retryable: payload.retryable,
            errorCode: payload.errorCode,
          }]);
        }
        if (payload.status && TERMINAL_STATUSES.includes(payload.status as ProcessingTaskStatus)) {
          void refreshAggregate();
        }
      } catch (e) {
        setError(formatError(e));
      }
    };

    return subscribeEventStream<TaskEventDTO>(processingTaskEventsUrl(taskId), {
      onEvent: handleEvent,
      onFallback,
    });
  };

  const refreshAggregate = async () => {
    if (!meetingId) return;
    const data = await getMeetingAggregate(meetingId);
    setAggregate(data);
    seedSteps(data.latestTask?.steps);
  };

  const handleExport = async () => {
    if (!meetingId) return;
    setBusyExport(true);
    setError(null);
    const controller = new AbortController();
    exportAbortRef.current = controller;
    try {
      const created = await createExport(meetingId, "DOCX");
      setExportJob(created);
      // Poll until terminal within a generous deadline. The old loop was a
      // fixed 30×1s: renders slower than 30s silently "failed" and the
      // operator had no way to cancel the wait.
      const deadline = Date.now() + EXPORT_WAIT_MS;
      while (Date.now() < deadline && !controller.signal.aborted) {
        const polled = await pollExport(meetingId, created.exportId);
        if (controller.signal.aborted) return;
        setExportJob(polled);
        if (polled.status === "SUCCEEDED" && polled.downloadUrl) return;
        if (["FAILED", "CANCELLED", "REVOKED"].includes(polled.status)) {
          throw new Error(`导出失败: ${polled.status}`);
        }
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, 2000);
          controller.signal.addEventListener("abort", () => {
            clearTimeout(timer);
            resolve();
          }, { once: true });
        });
      }
      if (!controller.signal.aborted) {
        setError("导出仍在后台进行，稍后可刷新页面查看下载链接");
      }
    } catch (e) {
      setError(formatError(e));
    } finally {
      exportAbortRef.current = null;
      setBusyExport(false);
    }
  };

  const cancelExportWait = () => {
    exportAbortRef.current?.abort();
  };

  const handleConfirmCandidate = async (speaker: MeetingSpeakerDTO, candidate: SpeakerCandidateDTO) => {
    if (!meetingId || typeof meeting?.transcriptVersion !== "number") return;
    const label = getSpeakerLabel(speaker);
    const key = `${label}:${candidate.personId}`;
    setConfirmingSpeaker(key);
    setError(null);
    try {
      await confirmSpeaker(meetingId, label, {
        personId: candidate.personId,
        speakerProfileId: candidate.speakerProfileId,
        expectedTranscriptVersion: meeting.transcriptVersion,
      });
      await refreshAggregate();
    } catch (e) {
      setError(formatError(e));
    } finally {
      setConfirmingSpeaker(null);
    }
  };

  const handleAddParticipant = async (person: PersonDTO) => {
    if (!meetingId || !meeting || typeof meeting.transcriptVersion !== "number") return;
    if (!person.personId || participants.some((participant) => participant.personId === person.personId)) return;
    const nextParticipants: MeetingParticipantDTO[] = [
      ...participants,
      { personId: person.personId, displayName: person.displayName, role: DEFAULT_PARTICIPANT_ROLE },
    ];
    setAddingParticipant(person.personId);
    setError(null);
    try {
      await updateMeeting(meetingId, {
        participants: nextParticipants,
        expectedVersion: meeting.transcriptVersion,
      });
      personSearch.reset();
      await refreshAggregate();
    } catch (e) {
      setError(formatError(e));
    } finally {
      setAddingParticipant(null);
    }
  };

  const handleConfirmReject = async () => {
    if (!meetingId || !pendingRejectSpeaker) return;
    const label = getSpeakerLabel(pendingRejectSpeaker);
    setRejectingSpeaker(label);
    setRejectError(null);
    setError(null);
    try {
      await rejectSpeaker(meetingId, label);
      setPendingRejectSpeaker(null);
      await refreshAggregate();
    } catch (e) {
      setRejectError(formatError(e));
    } finally {
      setRejectingSpeaker(null);
    }
  };

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">{meeting?.title ?? `会议 ${meetingId}`}</h1>
          <p className="page-subtitle">
            {meeting?.language ?? "zh"} · {meetingId}
          </p>
        </div>
        {task ? (
          <div className="toolbar">
            <span className="pill pill--info">{task.phase}</span>
            <span className={`pill ${statusPill(task.status)}`}>{task.status}</span>
          </div>
        ) : null}
      </header>

      {error ? <div className="banner banner--danger" role="alert">{error}</div> : null}
      {aggregate?.degraded?.length ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">部分数据加载失败</strong>
          <span className="banner__body">
            {aggregate.degraded.map((name) => DEGRADED_LABELS[name] ?? name).join("、")}
            暂时不可用（上游服务异常，并非“暂无数据”）。
          </span>
          <button className="button" type="button" onClick={() => void refreshAggregate()}>重试</button>
        </div>
      ) : null}

      <section className="card stack" aria-labelledby="meeting-participants">
        <h2 id="meeting-participants">参会人</h2>
        {participants.length ? (
          <div className="toolbar" aria-live="polite">
            {participants.map((participant) => (
              <span key={participant.personId} className="chip">
                {participant.displayName}
              </span>
            ))}
          </div>
        ) : (
          <p className="page-subtitle">暂无参会人。</p>
        )}
        <div className="field">
          <label className="field__label" htmlFor="meeting-person-search">搜索人员</label>
          <input
            id="meeting-person-search"
            type="search"
            name="personSearch"
            className="input"
            placeholder="按姓名 / 邮箱搜索…"
            onChange={(event) => personSearch.search(event.target.value)}
            autoComplete="off"
          />
        </div>
        {personSearch.loading ? <p className="page-subtitle" aria-live="polite">搜索中…</p> : null}
        {personSearch.error ? <p className="error" role="alert">{formatError(personSearch.error)}</p> : null}
        {personResults.length ? (
          <div className="stack">
            {personResults.map((person) => {
              const alreadyAdded = participants.some((participant) => participant.personId === person.personId);
              return (
                <div key={person.personId} className="toolbar">
                  <span>{person.displayName}</span>
                  {person.email ? <span className="page-subtitle">{person.email}</span> : null}
                  <button
                    className="button button--secondary"
                    type="button"
                    aria-label={`${alreadyAdded ? "已添加" : "添加"} ${person.displayName}`}
                    disabled={alreadyAdded || addingParticipant === person.personId}
                    onClick={() => void handleAddParticipant(person)}
                  >
                    {alreadyAdded ? "已添加" : addingParticipant === person.personId ? "添加中…" : "添加"}
                  </button>
                </div>
              );
            })}
          </div>
        ) : null}
      </section>

      <section className="card stack" aria-labelledby="meeting-progress">
        <h2 id="meeting-progress">流水线进度</h2>
        <div className="step-grid">
          {sortedSteps.map((step) => (
            <div key={step.stepName} className="step-cell" data-testid={`step-${step.stepName}`}>
              <strong>{step.stepName}</strong>
              <span className={`pill ${statusPill(step.status)}`}>{step.status}</span>
              <progress value={normalizeProgress(step.progress)} max={100} aria-label={`${step.stepName} progress`} />
              {step.errorCode ? <span className="error">{step.errorCode}</span> : null}
            </div>
          ))}
        </div>
      </section>

      {task?.status === "PARTIAL_SUCCEEDED" ? (
        <div className="banner banner--warn" role="status">
          <strong className="banner__title">部分成功</strong>
          <span className="banner__body">{task.lastErrorCode ?? "存在失败步骤，可按后端策略重试。"}</span>
        </div>
      ) : null}

      {terminalContentVisible ? (
        <>
          <section className="card stack" aria-labelledby="meeting-speakers">
            <h2 id="meeting-speakers">说话人</h2>
            {aggregate?.speakers?.speakers.length ? (
              <div className="stack">
                {aggregate.speakers.speakers.map((speaker, index) => {
                  const label = getSpeakerLabel(speaker);
                  const confirmationBadge = getSpeakerConfirmationBadge(speaker);
                  return (
                    <div key={getSpeakerKey(speaker, index)} className="toolbar">
                      <strong>{label}</strong>
                      <span>{speaker.displayName || "未认定"}</span>
                      {confirmationBadge ? (
                        <span className={`pill ${confirmationBadge.tone}`}>{confirmationBadge.label}</span>
                      ) : null}
                      {canConfirmSpeaker(speaker) ? (
                        <div className="toolbar" aria-label={`${label} 候选人`}>
                          {speaker.candidates?.map((candidate) => (
                            <button
                              key={`${candidate.personId}:${candidate.speakerProfileId}`}
                              className="button button--secondary"
                              type="button"
                              disabled={confirmingSpeaker === `${label}:${candidate.personId}`}
                              onClick={() => void handleConfirmCandidate(speaker, candidate)}
                            >
                              认定 {candidate.displayName} {candidate.confidence.toFixed(2)}
                            </button>
                          ))}
                          {canRejectSpeaker(speaker) ? (
                            <button
                              className="button button--ghost"
                              type="button"
                              disabled={rejectingSpeaker === label}
                              onClick={() => {
                                setRejectError(null);
                                setPendingRejectSpeaker(speaker);
                              }}
                            >
                              驳回 {label}
                            </button>
                          ) : null}
                        </div>
                      ) : null}
                      {canEnrollSpeaker(speaker) ? (
                        <Link
                          className="button button--secondary"
                          to={`/enrollment?personId=${encodeURIComponent(speaker.personId ?? "")}&returnTo=${encodeURIComponent(`/meetings/${meetingId}`)}`}
                        >
                          为 {speaker.displayName ?? speaker.personId} 录入声纹
                        </Link>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className="page-subtitle">暂无说话人结果。</p>
            )}
          </section>

          {aggregate?.minutes?.markdown ? (
            <section className="card stack" aria-labelledby="meeting-minutes">
              <h2 id="meeting-minutes">纪要</h2>
              <SafeMarkdown source={aggregate.minutes.markdown} />
            </section>
          ) : null}
        </>
      ) : null}

      <section className="card stack" aria-labelledby="meeting-export">
        <h2 id="meeting-export">导出</h2>
        <div className="toolbar">
          <button className="button button--primary" type="button" data-testid="export-docx" disabled={busyExport} onClick={() => void handleExport()}>
            {busyExport ? "导出中…" : "创建 docx"}
          </button>
          {busyExport ? (
            <button className="button" type="button" onClick={cancelExportWait}>
              取消等待
            </button>
          ) : null}
          {exportJob ? <span className="pill pill--info" data-testid="export-status">{exportJob.status}</span> : null}
          {exportJob?.status === "SUCCEEDED" && exportJob.downloadUrl ? (
            <a className="button button--secondary" href={exportJob.downloadUrl} download data-testid="download-link">下载</a>
          ) : null}
        </div>
      </section>

      {pendingRejectSpeaker ? (
        <div className="modal" role="presentation">
          <section
            className="modal__panel stack"
            role="dialog"
            aria-modal="true"
            aria-labelledby="speaker-reject-title"
          >
            <header className="page-header">
              <div>
                <h2 id="speaker-reject-title" className="page-title">驳回说话人候选</h2>
                <p className="page-subtitle">
                  将保留原始 SPEAKER 标签，不会把这些候选人写入转写和纪要。
                </p>
              </div>
            </header>
            <div className="banner banner--warn">
              <strong className="banner__title">{getSpeakerLabel(pendingRejectSpeaker)}</strong>
              <span className="banner__body">
                {pendingRejectSpeaker.candidates?.map((candidate) => candidate.displayName).join(" / ") || "无候选人"}
              </span>
            </div>
            {rejectError ? (
              <div className="banner banner--danger" role="alert">{rejectError}</div>
            ) : null}
            <footer className="toolbar">
              <button
                className="button button--ghost"
                type="button"
                disabled={!!rejectingSpeaker}
                onClick={() => {
                  setRejectError(null);
                  setPendingRejectSpeaker(null);
                }}
              >
                取消
              </button>
              <button
                className="button button--danger"
                type="button"
                disabled={!!rejectingSpeaker}
                onClick={() => void handleConfirmReject()}
              >
                确认驳回
              </button>
            </footer>
          </section>
        </div>
      ) : null}
    </div>
  );
}

function normalizeStep(step: ProcessingTaskStepDTO): ProcessingTaskStepDTO {
  return { ...step, progress: normalizeProgress(step.progress) };
}

function normalizeProgress(progress: number): number {
  return progress <= 1 ? Math.round(progress * 100) : progress;
}

function statusPill(status: string) {
  if (["SUCCEEDED", "COMPLETED"].includes(status)) return "pill--success";
  if (["FAILED", "CANCELLED", "REVOKED"].includes(status)) return "pill--danger";
  if (["PARTIAL_SUCCEEDED", "RUNNING", "QUEUED", "PENDING"].includes(status)) return "pill--warn";
  return "pill--neutral";
}

function getSpeakerLabel(speaker: MeetingSpeakerDTO): string {
  return speaker.speakerLabel || speaker.label || "SPEAKER";
}

function getSpeakerKey(speaker: MeetingSpeakerDTO, index: number): string {
  return speaker.speakerProfileId || speaker.personId || getSpeakerLabel(speaker) || String(index);
}

function getSpeakerConfirmationBadge(speaker: MeetingSpeakerDTO): { label: string; tone: string } | null {
  if (speaker.confirmationStatus === "AUTO_CONFIRMED") {
    return { label: "自动认定", tone: "pill--success" };
  }
  if (speaker.confirmationStatus === "MANUALLY_CONFIRMED") {
    return { label: "人工认定", tone: "pill--info" };
  }
  if (speaker.confirmationStatus === "CONFIRMED" ||
    (!speaker.confirmationStatus && speaker.verificationStatus === "CONFIRMED")) {
    return { label: "已认定", tone: "pill--success" };
  }
  if (speaker.confirmationStatus === "REJECTED" || speaker.verificationStatus === "REJECTED") {
    return { label: "已驳回", tone: "pill--danger" };
  }
  return null;
}

function hasFinalSpeakerDecision(speaker: MeetingSpeakerDTO): boolean {
  return !!getSpeakerConfirmationBadge(speaker);
}

function canConfirmSpeaker(speaker: MeetingSpeakerDTO): boolean {
  return !hasFinalSpeakerDecision(speaker) && !!speaker.candidates?.length;
}

function canRejectSpeaker(speaker: MeetingSpeakerDTO): boolean {
  return !hasFinalSpeakerDecision(speaker) && !!speaker.candidates?.length;
}

function canEnrollSpeaker(speaker: MeetingSpeakerDTO): boolean {
  return !hasFinalSpeakerDecision(speaker) &&
    !!speaker.personId &&
    !speaker.speakerProfileId &&
    !speaker.candidates?.length;
}

