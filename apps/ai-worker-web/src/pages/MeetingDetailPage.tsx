import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { ApiError } from "@/shared/api/client";
import { subscribeEventStream, type EventStreamSubscription } from "@/shared/api/client";
import { confirmSpeaker, createExport, getMeetingAggregate, pollExport, processingTaskEventsUrl } from "@/shared/api/endpoints";
import type {
  ExportJobDTO,
  MeetingAggregateDTO,
  MeetingSpeakerDTO,
  SpeakerCandidateDTO,
  ProcessingTaskStepDTO,
  ProcessingTaskStatus,
  TaskEventDTO,
} from "@/shared/api/types";
import { SafeMarkdown } from "@/shared/markdown/SafeMarkdown";

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

export function MeetingDetailPage() {
  const { meetingId = "" } = useParams<{ meetingId: string }>();
  const [aggregate, setAggregate] = useState<MeetingAggregateDTO | null>(null);
  const [steps, setSteps] = useState<Record<string, ProcessingTaskStepDTO>>({});
  const [exportJob, setExportJob] = useState<ExportJobDTO | null>(null);
  const [busyExport, setBusyExport] = useState(false);
  const [confirmingSpeaker, setConfirmingSpeaker] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const task = aggregate?.latestTask?.data ?? null;
  const meeting = aggregate?.meeting?.data ?? null;
  const isTerminal = task ? TERMINAL_STATUSES.includes(task.status) : false;
  const terminalContentVisible = isTerminal || !!aggregate?.minutes?.data || !!aggregate?.speakers?.data;

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
        seedSteps(data.latestTask?.data?.steps);
        const taskId = data.latestTask?.data?.taskId;
        if (taskId && !eventStream) {
          eventStream = openTaskEvents(taskId, () => {
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

  const securityBlocked = task?.lastErrorCode === "SECURITY_LEVEL_BLOCKED" ||
    Object.values(steps).some((step) => step.errorCode === "SECURITY_LEVEL_BLOCKED");

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
    seedSteps(data.latestTask?.data?.steps);
  };

  const handleExport = async () => {
    if (!meetingId) return;
    setBusyExport(true);
    setError(null);
    try {
      const created = await createExport(meetingId, "DOCX");
      setExportJob(created);
      for (let attempt = 0; attempt < 30; attempt += 1) {
        const polled = await pollExport(meetingId, created.exportId);
        setExportJob(polled);
        if (polled.status === "SUCCEEDED" && polled.downloadUrl) return;
        if (["FAILED", "CANCELLED", "REVOKED"].includes(polled.status)) {
          throw new Error(`导出失败: ${polled.status}`);
        }
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusyExport(false);
    }
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

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">{meeting?.title ?? `会议 ${meetingId}`}</h1>
          <p className="page-subtitle">
            {meeting?.securityLevel ?? "INTERNAL"} · {meeting?.language ?? "zh"} · {meetingId}
          </p>
        </div>
        {task ? (
          <div className="toolbar">
            <span className="pill pill--info">{task.phase}</span>
            <span className={`pill ${statusPill(task.status)}`}>{task.status}</span>
          </div>
        ) : null}
      </header>

      {securityBlocked ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">SECURITY_LEVEL_BLOCKED</strong>
          <span className="banner__body">当前安全级别阻断了 LLM 处理。</span>
        </div>
      ) : null}
      {error ? <div className="banner banner--danger" role="alert">{error}</div> : null}

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
            {aggregate?.speakers?.data?.length ? (
              <div className="stack">
                {aggregate.speakers.data.map((speaker, index) => {
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
                        </div>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className="page-subtitle">暂无说话人结果。</p>
            )}
          </section>

          {aggregate?.minutes?.data?.markdown ? (
            <section className="card stack" aria-labelledby="meeting-minutes">
              <h2 id="meeting-minutes">纪要</h2>
              <SafeMarkdown source={aggregate.minutes.data.markdown} />
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
          {exportJob ? <span className="pill pill--info" data-testid="export-status">{exportJob.status}</span> : null}
          {exportJob?.status === "SUCCEEDED" && exportJob.downloadUrl ? (
            <a className="button button--secondary" href={exportJob.downloadUrl} download data-testid="download-link">下载</a>
          ) : null}
        </div>
      </section>
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
  return null;
}

function isConfirmedSpeaker(speaker: MeetingSpeakerDTO): boolean {
  return !!getSpeakerConfirmationBadge(speaker);
}

function canConfirmSpeaker(speaker: MeetingSpeakerDTO): boolean {
  return !isConfirmedSpeaker(speaker) && !!speaker.candidates?.length;
}

function formatError(e: unknown): string {
  if (e instanceof ApiError) return `${e.error.code}: ${e.error.message}`;
  if (e instanceof Error) return e.message;
  return String(e);
}
