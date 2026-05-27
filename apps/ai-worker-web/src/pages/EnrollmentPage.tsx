import { useCallback, useState } from "react";
import {
  commitEnrollment,
  createEnrollmentSession,
  previewEnrollment,
  searchPersons,
  uploadEnrollmentAudio,
} from "@/shared/api/endpoints";
import type { EnrollmentSessionDTO, PersonDTO } from "@/shared/api/types";
import { ApiError } from "@/shared/api/client";
import { useDebouncedSearch } from "@/shared/hooks/useDebouncedSearch";

const QUALITY_THRESHOLD = 0.5;

export function EnrollmentPage() {
  const [personId, setPersonId] = useState<string | null>(null);
  const [session, setSession] = useState<EnrollmentSessionDTO | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const searchPersonsFetcher = useCallback(
    (q: string, signal: AbortSignal) => searchPersons(q, { signal }),
    [],
  );
  const personSearch = useDebouncedSearch<PersonDTO>(searchPersonsFetcher);
  const persons = personSearch.results ?? [];

  const handleStart = async () => {
    setBusy(true);
    setError(null);
    try {
      const s = await createEnrollmentSession(personId);
      setSession(s);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
    }
  };

  const handleUploadAndPreview = async () => {
    if (!session || !file) return;
    setBusy(true);
    setError(null);
    try {
      await uploadEnrollmentAudio(session.sessionId, file);
      const previewed = await previewEnrollment(session.sessionId);
      setSession(previewed);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
    }
  };

  const handleCommit = async () => {
    if (!session) return;
    setBusy(true);
    setError(null);
    try {
      const committed = await commitEnrollment(session.sessionId);
      setSession(committed);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
    }
  };

  const qualityScore = session?.qualityScore;
  const qualityHigh = typeof qualityScore === "number" && qualityScore >= QUALITY_THRESHOLD;

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">声纹录入</h1>
          <p className="page-subtitle">为人员录入声纹样本。质量分须 ≥ {QUALITY_THRESHOLD.toFixed(1)} 才能确认。</p>
        </div>
      </header>

      <section className="card stack" aria-labelledby="enroll-step-1">
        <h2 id="enroll-step-1">1 · 选择人员</h2>
        <div className="field">
          <label className="field__label" htmlFor="enroll-person-search">搜索人员</label>
          <input
            id="enroll-person-search"
            name="personSearch"
            className="input"
            placeholder="按姓名 / 邮箱搜索…"
            onChange={(e) => personSearch.search(e.target.value)}
            autoComplete="off"
          />
        </div>
        {personSearch.loading ? <p className="page-subtitle" aria-live="polite">搜索中…</p> : null}
        {persons.length > 0 ? (
          <ul style={{ listStyle: "none", padding: 0, display: "flex", flexDirection: "column", gap: 6 }}>
            {persons.map((p) => (
              <li key={p.id}>
                <label className="toolbar">
                  <input
                    type="radio"
                    name="person"
                    value={p.id}
                    checked={personId === p.id}
                    onChange={() => setPersonId(p.id)}
                  />
                  <span>{p.displayName}</span>
                  {p.email ? <span className="page-subtitle">{p.email}</span> : null}
                </label>
              </li>
            ))}
          </ul>
        ) : null}
      </section>

      <section className="card stack" aria-labelledby="enroll-step-2">
        <h2 id="enroll-step-2">2 · 上传并预览</h2>
        <button
          className="button"
          onClick={() => void handleStart()}
          disabled={!personId || busy || !!session}
        >
          创建录入会话
        </button>
        {session && (
          <p className="page-subtitle" data-testid="session-id">
            会话: <span translate="no">{session.sessionId}</span> · 状态: {session.state}
          </p>
        )}
        <label htmlFor="enrollment-audio-file" className="upload-dropzone">
          <input
            id="enrollment-audio-file"
            type="file"
            accept="audio/*"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            disabled={!session}
            className="upload-dropzone__input"
            name="enrollmentAudio"
          />
          <span className="upload-dropzone__icon">📁</span>
          <span className="upload-dropzone__label">
            {file ? file.name : "点击选择音频文件 (MP3, WAV, M4A)"}
          </span>
          {file ? (
            <span className="page-subtitle">{(file.size / 1024 / 1024).toFixed(2)} MB</span>
          ) : null}
        </label>
        <button
          className="button button--primary"
          onClick={() => void handleUploadAndPreview()}
          disabled={!session || !file || busy}
        >
          {busy ? "处理中…" : "上传并预览"}
        </button>
      </section>

      <section className="card stack" aria-labelledby="enroll-step-3">
        <h2 id="enroll-step-3">3 · 质量确认</h2>
        {typeof qualityScore === "number" ? (
          <div className="toolbar" data-testid="quality-score">
            <span
              className={`pill ${qualityHigh ? "pill--success" : "pill--warn"}`}
              aria-label="quality-score"
            >
              质量分 {qualityScore.toFixed(2)}
            </span>
            {!qualityHigh ? (
              <span className="page-subtitle">⚠️ 分数偏低，建议重录</span>
            ) : null}
          </div>
        ) : (
          <p className="page-subtitle">上传并预览后此处显示质量评分。</p>
        )}
        <button
          className="button button--primary"
          onClick={() => void handleCommit()}
          disabled={!session || session.state !== "PREVIEWED" || busy}
        >
          确认录入
        </button>
      </section>

      {error || personSearch.error ? (
        <div className="error" role="alert">
          {error ?? formatError(personSearch.error)}
        </div>
      ) : null}
    </div>
  );
}

function formatError(e: unknown): string {
  if (e instanceof ApiError) {
    return `${e.error.code}: ${e.error.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}
