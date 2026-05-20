import { useState } from "react";
import { ApiError } from "@/shared/api/client";
import {
  attachMeetingDocument,
  confirmSpeaker,
  createExport,
  createMeeting,
  finalizeMeeting,
  getMeetingAggregate,
  pollExport,
  searchDocuments,
  startMeetingProcessing,
  updateMeetingGlossary,
} from "@/shared/api/endpoints";
import type {
  DocumentSummaryDTO,
  ExportJobDTO,
  GlossaryTermDTO,
  MeetingAggregateDTO,
} from "@/shared/api/types";
import { Stepper } from "@/features/wizard/Stepper";
import { useWizard } from "@/features/wizard/useWizard";
import { SafeMarkdown } from "@/shared/markdown/SafeMarkdown";
import { VirtualList } from "@/shared/list/VirtualList";

const VIRTUALIZE_THRESHOLD = 50;

export function MeetingWorkstationPage() {
  const { state, step, patch, goNext, order } = useWizard();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // STEP 1 — meeting metadata
  const [title, setTitle] = useState("");
  const [securityLevel, setSecurityLevel] = useState<"PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "SECRET">("INTERNAL");

  const handleCreateMeeting = run(async () => {
    if (!title.trim()) throw new Error("请填写标题");
    const meeting = await createMeeting({
      title,
      securityLevel,
      language: "zh",
      participants: [],
    });
    patch({ meetingId: meeting.meetingId });
    goNext();
  });

  // STEP 3a — glossary
  const [glossaryDraft, setGlossaryDraft] = useState("");
  const [terms, setTerms] = useState<GlossaryTermDTO[]>([]);
  const addTerm = () => {
    const t = glossaryDraft.trim();
    if (!t || terms.length >= 200 || terms.some((x) => x.term.toLowerCase() === t.toLowerCase())) {
      return;
    }
    setTerms([...terms, { term: t, aliases: [] }]);
    setGlossaryDraft("");
  };
  const saveGlossary = run(async () => {
    if (!state.meetingId) return;
    await updateMeetingGlossary(state.meetingId, terms);
    goNext();
  });

  // STEP 3b — documents
  const [docResults, setDocResults] = useState<DocumentSummaryDTO[]>([]);
  const [attachedDocs, setAttachedDocs] = useState<string[]>([]);
  const handleDocSearch = run(async (q: string) => {
    const r = await searchDocuments(q);
    setDocResults(r);
  });
  const handleAttachDoc = run(async (documentId: string) => {
    if (!state.meetingId) return;
    await attachMeetingDocument(state.meetingId, { documentId, role: "REFERENCE" });
    setAttachedDocs((prev) => (prev.includes(documentId) ? prev : [...prev, documentId]));
  });

  // STEP 4 — start processing
  const handleStartProcessing = run(async () => {
    if (!state.meetingId) return;
    await startMeetingProcessing(state.meetingId);
    patch({ startedProcessing: true });
    // Stay on the page so the user (and tests) can observe the transition before moving on.
  });

  // STEP 5 — speakers
  const [aggregate, setAggregate] = useState<MeetingAggregateDTO | null>(null);
  const handleLoadAggregate = run(async () => {
    if (!state.meetingId) return;
    const agg = await getMeetingAggregate(state.meetingId);
    setAggregate(agg);
  });
  const handleConfirmSpeaker = run(async (label: string, personId: string) => {
    if (!state.meetingId) return;
    await confirmSpeaker(state.meetingId, label, personId);
    await handleLoadAggregate();
  });

  // STEP 6a — finalize
  const handleFinalize = run(async () => {
    if (!state.meetingId) return;
    await finalizeMeeting(state.meetingId);
    patch({ finalized: true });
    // Don't auto-advance; let the user observe the transition before going to export.
  });

  // STEP 6c — export
  const [exportJob, setExportJob] = useState<ExportJobDTO | null>(null);
  const handleCreateExport = run(async () => {
    if (!state.meetingId) return;
    const job = await createExport(state.meetingId, "DOCX");
    setExportJob(job);
    patch({ exportId: job.exportId });
    // Naive polling — production code virtualizes with TanStack Query.
    for (let i = 0; i < 30; i++) {
      const polled = await pollExport(state.meetingId, job.exportId);
      setExportJob(polled);
      if (polled.status === "SUCCEEDED" && polled.downloadUrl) {
        patch({ downloadUrl: polled.downloadUrl });
        return;
      }
      if (polled.status === "FAILED" || polled.status === "CANCELLED") {
        throw new Error(`导出失败: ${polled.status}`);
      }
      await new Promise((res) => setTimeout(res, 1000));
    }
  });

  function run<TArgs extends unknown[], TReturn>(fn: (...args: TArgs) => Promise<TReturn>) {
    return async (...args: TArgs) => {
      setBusy(true);
      setError(null);
      try {
        return await fn(...args);
      } catch (e) {
        setError(formatError(e));
      } finally {
        setBusy(false);
      }
    };
  }

  return (
    <div>
      <h1>会议工作站向导</h1>
      <Stepper step={step} order={order} />
      {error && (
        <div className="error" role="alert" data-testid="wizard-error">
          {error}
        </div>
      )}

      {step === "META" && (
        <section className="card" aria-labelledby="step-meta-h">
          <h2 id="step-meta-h">建会议</h2>
          <div className="stack">
            <label>标题<input className="input" value={title} onChange={(e) => setTitle(e.target.value)} aria-label="meeting title" /></label>
            <label>安全级别
              <select className="select" value={securityLevel} onChange={(e) => setSecurityLevel(e.target.value as typeof securityLevel)} aria-label="security level">
                <option value="PUBLIC">PUBLIC</option>
                <option value="INTERNAL">INTERNAL</option>
                <option value="CONFIDENTIAL">CONFIDENTIAL</option>
                <option value="SECRET">SECRET</option>
              </select>
            </label>
          </div>
          <button className="button" disabled={busy} onClick={() => void handleCreateMeeting()}>下一步：上传录音</button>
        </section>
      )}

      {step === "AUDIO" && (
        <section className="card">
          <h2>上传录音</h2>
          <p>多分片上传请直连 Java：<code>POST /api/meetings/{state.meetingId}/files/audio/uploads</code></p>
          <button className="button" onClick={() => goNext()}>跳到术语</button>
        </section>
      )}

      {step === "GLOSSARY" && (
        <section className="card">
          <h2>术语</h2>
          <div className="row">
            <input
              className="input"
              value={glossaryDraft}
              onChange={(e) => setGlossaryDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addTerm(); } }}
              aria-label="term draft"
              placeholder="按 Enter 添加，单 term ≤ 64 字符，最多 200"
            />
            <button className="button button--secondary" onClick={addTerm} disabled={!glossaryDraft.trim()}>+ 添加</button>
          </div>
          <div>
            {terms.map((t) => (
              <span key={t.term} className="chip">
                {t.term}
                <button className="chip__remove" onClick={() => setTerms(terms.filter((x) => x.term !== t.term))}>×</button>
              </span>
            ))}
          </div>
          <button className="button" disabled={busy} onClick={() => void saveGlossary()}>保存并下一步</button>
        </section>
      )}

      {step === "DOCUMENTS" && (
        <section className="card">
          <h2>关联参考文档</h2>
          <input
            className="input"
            placeholder="搜索文档…"
            onChange={(e) => void handleDocSearch(e.target.value)}
            aria-label="document search"
          />
          {docResults.length > VIRTUALIZE_THRESHOLD ? (
            <VirtualList
              items={docResults}
              rowHeight={40}
              height={320}
              keyOf={(d) => d.documentId}
              testId="doc-results-virtual"
              renderRow={(d) => (
                <div>
                  {d.title} <small>({d.securityLevel})</small>
                  <button className="button button--secondary" disabled={attachedDocs.includes(d.documentId) || busy} onClick={() => void handleAttachDoc(d.documentId)}>关联</button>
                </div>
              )}
            />
          ) : (
            <ul>
              {docResults.map((d) => (
                <li key={d.documentId}>
                  {d.title} <small>({d.securityLevel})</small>
                  <button className="button button--secondary" disabled={attachedDocs.includes(d.documentId) || busy} onClick={() => void handleAttachDoc(d.documentId)}>关联</button>
                </li>
              ))}
            </ul>
          )}
          <p>已关联: {attachedDocs.length}</p>
          <button className="button" onClick={() => goNext()}>下一步：开始处理</button>
        </section>
      )}

      {step === "PROCESS" && (
        <section className="card">
          <h2>开始处理</h2>
          <button className="button" disabled={busy || state.startedProcessing} onClick={() => void handleStartProcessing()} data-testid="start-processing">
            {state.startedProcessing ? "已开始" : "开始处理（hold=true）"}
          </button>
          {state.startedProcessing && (
            <button className="button button--secondary" onClick={() => goNext()}>下一步：认人</button>
          )}
        </section>
      )}

      {step === "SPEAKERS" && (
        <section className="card">
          <h2>确认说话人</h2>
          <button className="button button--secondary" onClick={() => void handleLoadAggregate()}>刷新候选人</button>
          {aggregate?.speakers?.data?.length ? (
            (aggregate.speakers.data.length > VIRTUALIZE_THRESHOLD ? (
              <VirtualList
                items={aggregate.speakers.data}
                rowHeight={56}
                height={420}
                keyOf={(sp) => sp.label}
                testId="speakers-virtual"
                renderRow={(sp) => (
                  <div>
                    {sp.label} — {sp.displayName} ({sp.verificationStatus})
                    {sp.candidates.map((c) => (
                      <button
                        key={c.personId}
                        className="button button--secondary"
                        onClick={() => void handleConfirmSpeaker(sp.label, c.personId)}
                      >
                        认定为 {c.displayName} ({(c.confidence * 100).toFixed(0)}%)
                      </button>
                    ))}
                  </div>
                )}
              />
            ) : (
              <ul>
                {aggregate.speakers.data.map((sp) => (
                  <li key={sp.label}>
                    {sp.label} — {sp.displayName} ({sp.verificationStatus})
                    {sp.candidates.map((c) => (
                      <button
                        key={c.personId}
                        className="button button--secondary"
                        onClick={() => void handleConfirmSpeaker(sp.label, c.personId)}
                      >
                        认定为 {c.displayName} ({(c.confidence * 100).toFixed(0)}%)
                      </button>
                    ))}
                  </li>
                ))}
              </ul>
            ))
          ) : (
            <p>暂无候选人，请等待 worker 输出后再刷新。</p>
          )}
          <button className="button" onClick={() => goNext()}>跳到生成纪要</button>
        </section>
      )}

      {step === "FINALIZE" && (
        <section className="card">
          <h2>确认 → 生成纪要</h2>
          <button className="button" disabled={busy || state.finalized} onClick={() => void handleFinalize()} data-testid="finalize">
            {state.finalized ? "已 finalize" : "确认 → resume Java phase"}
          </button>
          {state.finalized && (
            <>
              <button className="button button--secondary" onClick={() => void handleLoadAggregate()}>刷新纪要</button>
              {aggregate?.minutes?.data?.markdown ? (
                <article className="card" aria-labelledby="minutes-h">
                  <h3 id="minutes-h">{aggregate.minutes.data.title || "会议纪要"}</h3>
                  <SafeMarkdown source={aggregate.minutes.data.markdown} testId="minutes-md" />
                </article>
              ) : (
                <p>纪要尚未生成，请稍后刷新。</p>
              )}
              <button className="button button--secondary" onClick={() => goNext()}>下一步：下载</button>
            </>
          )}
        </section>
      )}

      {step === "EXPORT" && (
        <section className="card">
          <h2>下载 docx</h2>
          <button className="button" disabled={busy || !!state.downloadUrl} onClick={() => void handleCreateExport()} data-testid="create-export">
            {state.downloadUrl ? "已就绪" : "创建导出"}
          </button>
          {exportJob && <p>状态: <span data-testid="export-status">{exportJob.status}</span></p>}
          {state.downloadUrl && (
            <p>
              <a className="button" href={state.downloadUrl} download data-testid="download-link">下载</a>
            </p>
          )}
        </section>
      )}
    </div>
  );
}

function formatError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.error.retryable) return `${e.error.code}: ${e.error.message}（可重试）`;
    return `${e.error.code}: ${e.error.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}
