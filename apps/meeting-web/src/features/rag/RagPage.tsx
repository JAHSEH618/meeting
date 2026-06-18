import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useRagScopeQuery, useRagAsk } from "./queries";
import { SafeMarkdown } from "@shared/components/SafeMarkdown";
import type {
  Document,
  Meeting,
  RagAnswerDTO,
  RagAnswerCoverage,
  DocumentChunkCitation,
  MeetingSegmentCitation,
} from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";
import { formatDate, formatMeetingStatus, formatMs } from "@shared/utils/formatters";

const MIN_TOP_N = 1;
const MAX_TOP_N = 20;
const DEFAULT_TOP_N = 8;

const COVERAGE_LABEL: Record<RagAnswerCoverage, string> = {
  TRANSCRIPT_ONLY: "仅会议记录",
  FULL: "会议 + 文档",
};

const COVERAGE_HINT: Record<RagAnswerCoverage, string> = {
  TRANSCRIPT_ONLY:
    "本次回答只引用了会议转写片段。若希望也覆盖文档知识库，请在范围中勾选相关文档或在文档页确认其索引状态。",
  FULL: "本次回答综合了会议转写与文档知识库。",
};

type Citation = MeetingSegmentCitation | DocumentChunkCitation;

function isMeetingCitation(c: Citation): c is MeetingSegmentCitation {
  return c.type === "MEETING_SEGMENT";
}

export function RagPage() {
  const [question, setQuestion] = useState("");
  const [topN, setTopN] = useState(DEFAULT_TOP_N);
  const [includeStale, setIncludeStale] = useState(false);

  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [selectedMeetingId, setSelectedMeetingId] = useState<string | null>(null);
  const [selectedDocuments, setSelectedDocuments] = useState<Set<string>>(new Set());

  const [answer, setAnswer] = useState<RagAnswerDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [scopeOpen, setScopeOpen] = useState(false);

  const scope = useRagScopeQuery();
  const ask = useRagAsk();

  useEffect(() => {
    if (scope.data) {
      setMeetings(scope.data.meetings);
      setDocuments(scope.data.documents);
      if (selectedMeetingId && !scope.data.meetings.some((meeting) => meeting.meetingId === selectedMeetingId)) {
        setSelectedMeetingId(null);
        setAnswer(null);
      }
    }
  }, [scope.data, selectedMeetingId]);

  const scopeLoadError = scope.error
    ? ((scope.error as ApiClientError).code
        ? getUserMessage((scope.error as ApiClientError).code!)
        : "范围数据加载失败")
    : null;

  const handleAsk = useCallback(async () => {
    if (!selectedMeetingId) {
      setError("请先选择会议");
      return;
    }
    if (!question.trim()) {
      setError("请先输入问题");
      return;
    }
    setError(null);
    try {
      const result = await ask.mutateAsync({
        question: question.trim(),
        scope: {
          meetingIds: [selectedMeetingId],
          documentIds: Array.from(selectedDocuments),
        },
        topN,
        includeStale,
      });
      setAnswer(result);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "知识问答查询失败");
      setAnswer(null);
    }
  }, [ask, question, selectedMeetingId, selectedDocuments, topN, includeStale]);

  const toggleDocument = (set: Set<string>, id: string): Set<string> => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  };

  const selectedMeeting = useMemo(
    () => meetings.find((meeting) => meeting.meetingId === selectedMeetingId) ?? null,
    [meetings, selectedMeetingId],
  );

  const scopeSummary = useMemo(() => {
    const d = selectedDocuments.size;
    const parts = ["当前会议"];
    if (d > 0) parts.push(`${d} 个文档`);
    return parts.join(" + ");
  }, [selectedDocuments]);

  const handleSelectMeeting = (meetingId: string) => {
    setSelectedMeetingId(meetingId);
    setQuestion("");
    setAnswer(null);
    setError(null);
  };

  return (
    <div className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">知识问答</span>
          <h1 className="page-hero__title">问答</h1>
          <p className="page-hero__subtitle">
            基于已索引的会议转写与文档生成带引用的回答。所有候选片段都会经过 Java 端的二次权限校验。
          </p>
        </div>
      </header>

      {error ? (
        <div className="error" role="alert">{error}</div>
      ) : null}

      {!selectedMeeting ? (
        <section className="glass-panel stack rag-meeting-panel" aria-labelledby="rag-meeting-title">
          <div className="toolbar control-row--between">
            <div className="stack">
              <h2 id="rag-meeting-title" className="card-title">选择会议</h2>
              <p className="page-subtitle">先确定要查询的会议，再基于该会议的转写内容发起问答。</p>
            </div>
            <Link className="button button--ghost" to="/meetings/new">新建会议</Link>
          </div>
          {scopeLoadError ? (
            <div className="error" role="alert">{scopeLoadError}</div>
          ) : null}
          {scope.isPending ? (
            <p className="page-subtitle" aria-live="polite">正在加载可选会议…</p>
          ) : null}
          {!scope.isPending && meetings.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <strong>暂无可问答的会议</strong>
              <span>先创建会议并完成音频处理后，再回到这里提问。</span>
            </div>
          ) : null}
          {meetings.length > 0 ? (
            <div className="rag-meeting-list" aria-label="可问答会议">
              {meetings.map((meeting) => (
                <button
                  key={meeting.meetingId}
                  type="button"
                  className="rag-meeting-option"
                  aria-label={`选择${meeting.title}`}
                  onClick={() => handleSelectMeeting(meeting.meetingId)}
                >
                  <span className="rag-meeting-option__title">{meeting.title}</span>
                  <span className="rag-meeting-option__meta">
                    <span className={`pill ${meeting.status === "SUCCEEDED" ? "pill--success" : "pill--neutral"}`}>
                      {formatMeetingStatus(meeting.status)}
                    </span>
                    <span>{formatDate(meeting.createdAt)}</span>
                  </span>
                </button>
              ))}
            </div>
          ) : null}
        </section>
      ) : (
        <section className="glass-panel control-panel">
        <div className="rag-selected-context">
          <strong>已选择会议：{selectedMeeting.title}</strong>
          <button
            type="button"
            className="button button--subtle button--compact"
            onClick={() => {
              setSelectedMeetingId(null);
              setQuestion("");
              setAnswer(null);
              setError(null);
            }}
          >
            重新选择
          </button>
        </div>

        <div className="field">
          <label className="field__label" htmlFor="rag-question">问题</label>
          <textarea
            id="rag-question"
            name="ragQuestion"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            rows={3}
            placeholder="例：上周关于价格策略的会议得出了什么结论？"
            aria-label="rag-question-input"
          />
        </div>

        <details
          className="disclosure"
          open={scopeOpen}
          onToggle={(e) => setScopeOpen((e.currentTarget as HTMLDetailsElement).open)}
        >
          <summary>
            <span>补充范围</span>
            <span className="disclosure-summary__meta">{scopeSummary}</span>
          </summary>
          {scopeLoadError ? (
            <div className="error" role="alert">{scopeLoadError}</div>
          ) : null}
          <div className="scope-grid">
            <fieldset className="scope-fieldset stack">
              <legend>可补充的文档</legend>
              {documents.length === 0 ? (
                <p className="page-subtitle">暂无可选文档</p>
              ) : (
                documents.map((doc) => (
                  <label key={doc.documentId} className="scope-option">
                    <input
                      className="control-checkbox"
                      type="checkbox"
                      checked={selectedDocuments.has(doc.documentId)}
                      onChange={() => setSelectedDocuments((s) => toggleDocument(s, doc.documentId))}
                    />
                    <span className="scope-option__content">
                      <span>{doc.title}</span>
                      <span className="scope-option__id">知识库文档</span>
                    </span>
                  </label>
                ))
              )}
            </fieldset>
          </div>
        </details>

        <div className="control-row">
          <label className="control-label">
            <span>检索条数</span>
            <input
              className="control-input"
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
              aria-label="检索条数"
            />
          </label>
          <label className="control-label">
            <input
              className="control-checkbox"
              type="checkbox"
              checked={includeStale}
              onChange={(e) => setIncludeStale(e.target.checked)}
              aria-label="includeStale"
            />
            <span>包含已过期片段</span>
          </label>
          <button
            type="button"
            className="button button--primary"
            onClick={() => void handleAsk()}
            disabled={ask.isPending}
          >
            {ask.isPending ? "查询中…" : "提问"}
          </button>
        </div>
      </section>
      )}

      {ask.isPending ? (
        <p className="page-subtitle" aria-live="polite">正在检索 + 推理…</p>
      ) : null}

      {answer ? <AnswerCard answer={answer} /> : null}
    </div>
  );
}

function AnswerCard({ answer }: { answer: RagAnswerDTO }) {
  const noCitations = answer.citations.length === 0;
  return (
    <div className="glass-panel stack" aria-label="rag-answer">
      <div className="toolbar">
        <strong>回答</strong>
        <span className="pill pill--info" aria-label="rag-coverage">
          {COVERAGE_LABEL[answer.coverage]}
        </span>
        {noCitations ? (
          <span className="pill pill--warn">无引用，仅供参考</span>
        ) : (
          <span className="pill pill--neutral">{answer.citations.length} 条引用</span>
        )}
      </div>
      <p className="page-subtitle">{COVERAGE_HINT[answer.coverage]}</p>
      <SafeMarkdown source={answer.answer} ariaLabel="rag-answer-body" />

      {noCitations ? (
        <p className="page-subtitle">
          模型未明确指明引用来源。回答可能基于隐含上下文，建议追问以确认依据。
        </p>
      ) : (
        <div className="stack">
          <strong>引用</strong>
          <ol className="stack" style={{ paddingLeft: 20 }}>
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
      <article className="glass-panel glass-panel--compact stack" aria-label={`citation-${index}`}>
        <div className="toolbar">
          <span className="pill pill--info">会议片段</span>
          <strong>{citation.meetingTitle}</strong>
          <span className="page-subtitle">{citation.speaker}</span>
          <span className="page-subtitle">{formatMs(citation.startMs)} – {formatMs(citation.endMs)}</span>
        </div>
        <blockquote style={{ margin: 0 }}>
          <SafeMarkdown source={citation.content} />
        </blockquote>
        <Link to={target}>跳转到转写片段 →</Link>
      </article>
    );
  }
  return (
    <article className="glass-panel glass-panel--compact stack" aria-label={`citation-${index}`}>
      <div className="toolbar">
        <span className="pill pill--info">文档块</span>
        <strong>{citation.documentTitle}</strong>
        {citation.page > 0 ? <span className="page-subtitle">第 {citation.page} 页</span> : null}
      </div>
      <blockquote style={{ margin: 0 }}>
        <SafeMarkdown source={citation.content} />
      </blockquote>
    </article>
  );
}
