import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
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
import { useDebouncedSearch } from "@/shared/hooks/useDebouncedSearch";
import { SafeMarkdown } from "@/shared/markdown/SafeMarkdown";
import { VirtualList } from "@/shared/list/VirtualList";

const VIRTUALIZE_THRESHOLD = 50;
const GLOSSARY_MAX_LENGTH = 64;
const GLOSSARY_MAX_TERMS = 200;

export function MeetingWorkstationPage() {
  // Deep links land on /workstation/meetings/:meetingId — without reading
  // the route param the wizard started from META and ignored the meeting
  // the user was actually trying to view. useWizard already supports an
  // initial meetingId (starts at AUDIO), we just had to plumb it through.
  const params = useParams<{ meetingId?: string }>();
  const routeMeetingId =
    params.meetingId && params.meetingId !== "new" ? params.meetingId : undefined;
  const navigate = useNavigate();
  const { state, step, patch, goNext, order } = useWizard(
    routeMeetingId ? { meetingId: routeMeetingId } : undefined,
  );
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
    // Bounce to the canonical URL so a refresh / copy-link keeps the meeting
    // context. The MeetingWorkstationRoute wrapper keys on the route param,
    // so this remounts the page; the new instance reads meetingId from the
    // URL via useWizard initial and starts at AUDIO automatically — no need
    // for goNext() on the (about-to-unmount) old instance.
    navigate(`/meetings/${meeting.meetingId}`, { replace: true });
  });

  // STEP 3a — glossary
  const [glossaryDraft, setGlossaryDraft] = useState("");
  const [glossaryError, setGlossaryError] = useState<string | null>(null);
  const [terms, setTerms] = useState<GlossaryTermDTO[]>([]);
  const addTerm = () => {
    const t = glossaryDraft.trim();
    if (!t) return;
    if (t.length > GLOSSARY_MAX_LENGTH) {
      setGlossaryError(`单个 term 不能超过 ${GLOSSARY_MAX_LENGTH} 字符（当前 ${t.length}）`);
      return;
    }
    if (terms.length >= GLOSSARY_MAX_TERMS) {
      setGlossaryError(`术语数已达上限 ${GLOSSARY_MAX_TERMS}`);
      return;
    }
    if (terms.some((x) => x.term.toLowerCase() === t.toLowerCase())) {
      setGlossaryError(`已存在术语「${t}」`);
      return;
    }
    setTerms([...terms, { term: t, aliases: [] }]);
    setGlossaryDraft("");
    setGlossaryError(null);
  };
  const removeTerm = (term: string) => {
    setTerms(terms.filter((x) => x.term !== term));
    setGlossaryError(null);
  };
  const saveGlossary = run(async () => {
    if (!state.meetingId) return;
    await updateMeetingGlossary(state.meetingId, terms);
    goNext();
  });

  // STEP 3b — documents (debounced + abortable search to avoid lost-update
  // races where a slow response for "A" lands after the user has typed "ABC").
  const searchDocumentsFetcher = useCallback(
    (q: string, signal: AbortSignal) => searchDocuments(q, { signal }),
    [],
  );
  const docSearch = useDebouncedSearch<DocumentSummaryDTO>(searchDocumentsFetcher);
  const docResults = docSearch.results ?? [];
  const [attachedDocs, setAttachedDocs] = useState<string[]>([]);
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

  // When opened via deep link (/workstation/meetings/{id}) the user expects
  // to see existing speakers / minutes immediately, not the empty SPEAKERS
  // shell. Auto-load the aggregate once on mount; subsequent loads use the
  // explicit "刷新" buttons so we don't pummel the Java side.
  useEffect(() => {
    if (!routeMeetingId) return;
    void handleLoadAggregate();
    // handleLoadAggregate is recreated on each render but the auto-load
    // is intentionally fire-once on mount; lint disable + deps comment
    // makes the intent explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeMeetingId]);

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
          <button className="button" disabled={busy} onClick={() => goNext()}>跳到术语</button>
        </section>
      )}

      {step === "GLOSSARY" && (
        <section className="card">
          <h2>术语</h2>
          <div className="row">
            <input
              className="input"
              value={glossaryDraft}
              maxLength={GLOSSARY_MAX_LENGTH}
              onChange={(e) => {
                setGlossaryDraft(e.target.value);
                if (glossaryError) setGlossaryError(null);
              }}
              onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addTerm(); } }}
              aria-label="term draft"
              aria-invalid={glossaryError ? true : undefined}
              aria-describedby={glossaryError ? "glossary-error" : undefined}
              placeholder={`按 Enter 添加，单 term ≤ ${GLOSSARY_MAX_LENGTH} 字符，最多 ${GLOSSARY_MAX_TERMS}`}
            />
            <button className="button button--secondary" onClick={addTerm} disabled={!glossaryDraft.trim()}>+ 添加</button>
          </div>
          {glossaryError && (
            <p id="glossary-error" className="error" role="alert" data-testid="glossary-error">
              {glossaryError}
            </p>
          )}
          <div>
            {terms.map((t) => (
              <span key={t.term} className="chip">
                {t.term}
                <button
                  className="chip__remove"
                  type="button"
                  onClick={() => removeTerm(t.term)}
                  aria-label={`删除术语 ${t.term}`}
                >
                  ×
                </button>
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
            onChange={(e) => docSearch.search(e.target.value)}
            aria-label="document search"
          />
          {docSearch.loading && <p>搜索中…</p>}
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
          <button className="button" disabled={busy} onClick={() => goNext()}>下一步：开始处理</button>
        </section>
      )}

      {step === "PROCESS" && (
        <section className="card">
          <h2>开始处理</h2>
          <button className="button" disabled={busy || state.startedProcessing} onClick={() => void handleStartProcessing()} data-testid="start-processing">
            {state.startedProcessing ? "已开始" : "开始处理（hold=true）"}
          </button>
          {state.startedProcessing && (
            <button className="button button--secondary" disabled={busy} onClick={() => goNext()}>下一步：认人</button>
          )}
        </section>
      )}

      {step === "SPEAKERS" && (
        <section className="card">
          <h2>确认说话人</h2>
          <button className="button button--secondary" disabled={busy} onClick={() => void handleLoadAggregate()}>刷新候选人</button>
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
                        disabled={busy}
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
                        disabled={busy}
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
          <button className="button" disabled={busy} onClick={() => goNext()}>跳到生成纪要</button>
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
              <button className="button button--secondary" disabled={busy} onClick={() => void handleLoadAggregate()}>刷新纪要</button>
              {aggregate?.minutes?.data?.markdown ? (
                <article className="card" aria-labelledby="minutes-h">
                  <h3 id="minutes-h">{aggregate.minutes.data.title || "会议纪要"}</h3>
                  <SafeMarkdown source={aggregate.minutes.data.markdown} testId="minutes-md" />
                </article>
              ) : (
                <p>纪要尚未生成，请稍后刷新。</p>
              )}
              <button className="button button--secondary" disabled={busy} onClick={() => goNext()}>下一步：下载</button>
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
