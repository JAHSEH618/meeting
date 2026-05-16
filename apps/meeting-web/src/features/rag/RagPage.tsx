import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  listDocuments,
  listMeetings,
  ragQuery,
  type ApiClientError,
} from "@shared/api/client";
import type {
  Document,
  Meeting,
  RagAnswerDTO,
  RagAnswerCoverage,
  DocumentChunkCitation,
  MeetingSegmentCitation,
} from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";

const MIN_TOP_N = 1;
const MAX_TOP_N = 20;
const DEFAULT_TOP_N = 8;

const COVERAGE_LABEL: Record<RagAnswerCoverage, string> = {
  TRANSCRIPT_ONLY: "仅会议记录",
  FULL: "会议 + 文档",
};

const COVERAGE_HINT: Record<RagAnswerCoverage, string> = {
  TRANSCRIPT_ONLY: "本次回答只引用了会议转写片段。若希望也覆盖文档知识库，请在范围中勾选相关文档或在文档页确认其索引状态。",
  FULL: "本次回答综合了会议转写与文档知识库。",
};

type Citation = MeetingSegmentCitation | DocumentChunkCitation;

function isMeetingCitation(c: Citation): c is MeetingSegmentCitation {
  return c.type === "MEETING_SEGMENT";
}

function formatMs(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
    : `${m}:${String(s).padStart(2, "0")}`;
}

export function RagPage() {
  const [question, setQuestion] = useState("");
  const [topN, setTopN] = useState(DEFAULT_TOP_N);
  const [includeStale, setIncludeStale] = useState(false);

  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [selectedMeetings, setSelectedMeetings] = useState<Set<string>>(new Set());
  const [selectedDocuments, setSelectedDocuments] = useState<Set<string>>(new Set());

  const [answer, setAnswer] = useState<RagAnswerDTO | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [scopeOpen, setScopeOpen] = useState(false);
  const [scopeLoadError, setScopeLoadError] = useState<string | null>(null);

  const loadScopeOptions = useCallback(async () => {
    setScopeLoadError(null);
    try {
      const [meetingsResp, documentsResp] = await Promise.all([
        listMeetings(),
        listDocuments(),
      ]);
      setMeetings(meetingsResp.items);
      setDocuments(documentsResp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setScopeLoadError(apiError.code ? getUserMessage(apiError.code) : "范围数据加载失败");
    }
  }, []);

  useEffect(() => {
    void loadScopeOptions();
  }, [loadScopeOptions]);

  const handleAsk = async () => {
    if (!question.trim()) {
      setError("请先输入问题");
      return;
    }
    setError(null);
    setPending(true);
    try {
      const result = await ragQuery({
        question: question.trim(),
        scope: {
          meetingIds: Array.from(selectedMeetings),
          documentIds: Array.from(selectedDocuments),
        },
        topN,
        includeStale,
      });
      setAnswer(result);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "RAG 查询失败");
      setAnswer(null);
    } finally {
      setPending(false);
    }
  };

  const toggle = (set: Set<string>, id: string): Set<string> => {
    const next = new Set(set);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    return next;
  };

  const scopeSummary = useMemo(() => {
    const m = selectedMeetings.size;
    const d = selectedDocuments.size;
    if (m === 0 && d === 0) return "全部可读范围";
    const parts: string[] = [];
    if (m > 0) parts.push(`${m} 个会议`);
    if (d > 0) parts.push(`${d} 个文档`);
    return parts.join(" + ");
  }, [selectedMeetings, selectedDocuments]);

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">RAG 问答</h1>
          <p className="muted">
            基于已索引的会议转写与文档生成带引用的回答。所有候选片段都会经过 Java 端的二次权限校验。
          </p>
        </div>
      </div>

      {error ? (
        <div className="error" role="alert">
          {error}
        </div>
      ) : null}

      <div className="card stack">
        <label className="stack">
          <span>问题</span>
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            rows={3}
            placeholder="例：上周关于价格策略的会议得出了什么结论？"
            aria-label="rag-question-input"
          />
        </label>

        <details
          open={scopeOpen}
          onToggle={(e) => setScopeOpen((e.currentTarget as HTMLDetailsElement).open)}
        >
          <summary>
            范围：<span className="muted">{scopeSummary}</span>
          </summary>
          {scopeLoadError ? (
            <div className="error" role="alert">
              {scopeLoadError}
            </div>
          ) : null}
          <div className="grid">
            <fieldset className="stack">
              <legend>会议</legend>
              {meetings.length === 0 ? (
                <p className="muted">暂无可选会议</p>
              ) : (
                meetings.map((mtg) => (
                  <label key={mtg.meetingId} className="toolbar">
                    <input
                      type="checkbox"
                      checked={selectedMeetings.has(mtg.meetingId)}
                      onChange={() =>
                        setSelectedMeetings((s) => toggle(s, mtg.meetingId))
                      }
                    />
                    <span>{mtg.title}</span>
                    <span className="muted">{mtg.meetingId}</span>
                  </label>
                ))
              )}
            </fieldset>
            <fieldset className="stack">
              <legend>文档</legend>
              {documents.length === 0 ? (
                <p className="muted">暂无可选文档</p>
              ) : (
                documents.map((doc) => (
                  <label key={doc.documentId} className="toolbar">
                    <input
                      type="checkbox"
                      checked={selectedDocuments.has(doc.documentId)}
                      onChange={() =>
                        setSelectedDocuments((s) => toggle(s, doc.documentId))
                      }
                    />
                    <span>{doc.title}</span>
                    <span className="muted">{doc.documentId}</span>
                  </label>
                ))
              )}
            </fieldset>
          </div>
        </details>

        <div className="toolbar">
          <label className="toolbar">
            <span>topN</span>
            <input
              type="number"
              min={MIN_TOP_N}
              max={MAX_TOP_N}
              value={topN}
              onChange={(e) => {
                const next = Number.parseInt(e.target.value, 10);
                if (Number.isFinite(next)) {
                  setTopN(Math.max(MIN_TOP_N, Math.min(MAX_TOP_N, next)));
                }
              }}
              aria-label="topN"
            />
          </label>
          <label className="toolbar">
            <input
              type="checkbox"
              checked={includeStale}
              onChange={(e) => setIncludeStale(e.target.checked)}
              aria-label="includeStale"
            />
            <span>包含 stale 片段</span>
          </label>
          <button
            type="button"
            className="button primary"
            onClick={() => void handleAsk()}
            disabled={pending}
          >
            {pending ? "查询中…" : "提问"}
          </button>
        </div>
      </div>

      {pending ? (
        <p className="muted" aria-live="polite">
          正在检索 + 推理…
        </p>
      ) : null}

      {answer ? <AnswerCard answer={answer} /> : null}
    </div>
  );
}

function AnswerCard({ answer }: { answer: RagAnswerDTO }) {
  const noCitations = answer.citations.length === 0;
  return (
    <div className="card stack" aria-label="rag-answer">
      <div className="toolbar">
        <strong>回答</strong>
        <span className="badge" aria-label="rag-coverage">
          {COVERAGE_LABEL[answer.coverage]}
        </span>
        {noCitations ? (
          <span className="badge" style={{ background: "#fdf2d9", color: "#92580c" }}>
            无引用 — 仅供参考
          </span>
        ) : null}
      </div>
      <p className="muted">{COVERAGE_HINT[answer.coverage]}</p>
      <pre style={{ whiteSpace: "pre-wrap", margin: 0 }}>{answer.answer}</pre>

      {noCitations ? (
        <p className="muted">
          模型未明确指明引用来源。回答可能基于隐含上下文，建议追问以确认依据。
        </p>
      ) : (
        <div className="stack">
          <strong>引用</strong>
          <ol className="stack">
            {answer.citations.map((citation, idx) => (
              <li key={`${citation.type}-${idx}`}>
                <CitationItem citation={citation} index={idx + 1} />
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}

function CitationItem({ citation, index }: { citation: Citation; index: number }) {
  if (isMeetingCitation(citation)) {
    const target = `/meetings/${citation.meetingId}/transcript?segmentId=${encodeURIComponent(
      citation.segmentId,
    )}&startMs=${citation.startMs}`;
    return (
      <article className="card stack" aria-label={`citation-${index}`}>
        <div className="toolbar">
          <span className="badge">会议片段</span>
          <strong>{citation.meetingTitle}</strong>
          <span className="muted">{citation.speaker}</span>
          <span className="muted">
            {formatMs(citation.startMs)} – {formatMs(citation.endMs)}
          </span>
        </div>
        <blockquote style={{ margin: 0 }}>{citation.content}</blockquote>
        <Link to={target}>跳转到转写片段 →</Link>
      </article>
    );
  }
  return (
    <article className="card stack" aria-label={`citation-${index}`}>
      <div className="toolbar">
        <span className="badge">文档块</span>
        <strong>{citation.documentTitle}</strong>
        {citation.page > 0 ? <span className="muted">第 {citation.page} 页</span> : null}
      </div>
      <blockquote style={{ margin: 0 }}>{citation.content}</blockquote>
    </article>
  );
}
