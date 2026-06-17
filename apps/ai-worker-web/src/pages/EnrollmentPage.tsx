import { useCallback, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
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
import { PersonCreateModal } from "@/shared/components/PersonCreateModal";

const QUALITY_THRESHOLD = 0.5;
const FILE_SIZE_FORMATTER = new Intl.NumberFormat("zh-CN", {
  maximumFractionDigits: 2,
  minimumFractionDigits: 2,
});

export function EnrollmentPage() {
  const [searchParams] = useSearchParams();
  const initialPersonId = searchParams.get("personId");
  const returnTo = getSafeReturnTo(searchParams.get("returnTo"));
  const [personId, setPersonId] = useState<string | null>(initialPersonId);
  const [selectedPerson, setSelectedPerson] = useState<PersonDTO | null>(null);
  const [session, setSession] = useState<EnrollmentSessionDTO | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [personModalOpen, setPersonModalOpen] = useState(false);

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
    if (!session || !file || session.state === "COMMITTED") return;
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
      const committed = await commitEnrollment(session.sessionId, personId);
      setSession(committed);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
    }
  };

  const handleRestart = () => {
    setSession(null);
    setFile(null);
    setError(null);
    setPersonId(null);
    setSelectedPerson(null);
  };

  const qualityScore = session?.qualityScore;
  const qualityHigh = typeof qualityScore === "number" && qualityScore >= QUALITY_THRESHOLD;
  const canCommit = session?.state === "PREVIEWED" && qualityHigh && !busy;
  const committed = session?.state === "COMMITTED";
  const selectedPersonLabel = selectedPerson?.displayName ?? personId;
  const sessionLocked = !!session;

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
        {sessionLocked && (
          <div className="banner banner--info" role="status">
            <span className="banner__body">会话已创建，人员已锁定</span>
            <button className="button button--secondary" type="button" onClick={handleRestart}>
              重新开始
            </button>
          </div>
        )}
        <div className="field">
          <label className="field__label" htmlFor="enroll-person-search">搜索人员</label>
          <input
            id="enroll-person-search"
            name="personSearch"
            className="input"
            placeholder="按姓名 / 邮箱搜索…"
            onChange={(e) => personSearch.search(e.target.value)}
            autoComplete="off"
            disabled={sessionLocked}
          />
        </div>
        {personSearch.loading ? <p className="page-subtitle" aria-live="polite">搜索中…</p> : null}
        {persons.length > 0 ? (
          <ul className="option-list">
            {persons.map((p) => (
              <li key={p.personId}>
                <label className="toolbar">
                  <input
                    type="radio"
                    name="person"
                    value={p.personId}
                    checked={personId === p.personId}
                    onChange={() => {
                      setPersonId(p.personId);
                      setSelectedPerson(p);
                    }}
                    disabled={sessionLocked}
                  />
                  <span>{p.displayName}</span>
                  {p.email ? <span className="page-subtitle">{p.email}</span> : null}
                </label>
              </li>
            ))}
          </ul>
        ) : null}
        <div className="toolbar">
          <button
            className="button button--secondary"
            type="button"
            onClick={() => setPersonModalOpen(true)}
            disabled={sessionLocked}
          >
            + 新建人员
          </button>
          {selectedPersonLabel ? (
            <span className="page-subtitle">
              已选择：<span translate={selectedPerson ? undefined : "no"}>{selectedPersonLabel}</span>
            </span>
          ) : null}
        </div>
        <PersonCreateModal
          open={personModalOpen}
          onClose={() => setPersonModalOpen(false)}
          onCreated={(person) => {
            setPersonId(person.personId);
            setSelectedPerson(person);
            setPersonModalOpen(false);
          }}
        />
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
            disabled={!session || committed}
            className="upload-dropzone__input"
            name="enrollmentAudio"
          />
          <span className="upload-dropzone__label">
            {file ? file.name : "点击选择音频文件 (MP3, WAV, M4A)"}
          </span>
          {file ? (
            <span className="page-subtitle">{FILE_SIZE_FORMATTER.format(file.size / 1024 / 1024)} MB</span>
          ) : null}
        </label>
        <button
          className="button button--primary"
          onClick={() => void handleUploadAndPreview()}
          disabled={!session || !file || busy || committed}
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
          disabled={!canCommit}
        >
          确认录入
        </button>
        {committed ? (
          <div
            className="banner banner--success"
            role="status"
            aria-live="polite"
            aria-label="录入已写入 Java 工作流"
          >
            <strong className="banner__title">录入已写入 Java 工作流</strong>
            <span className="banner__body">
              {session.profileId ? (
                <>
                  声纹档案 <span translate="no">{session.profileId}</span>
                </>
              ) : (
                "声纹档案已创建"
              )}
              {session.fileId ? (
                <>
                  {" · "}
                  音频文件 <span translate="no">{session.fileId}</span>
                </>
              ) : null}
            </span>
            {returnTo ? <Link className="button button--secondary" to={returnTo}>返回会议</Link> : null}
          </div>
        ) : null}
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
    // Handle specific enrollment errors with user-friendly messages
    if (e.error.code === "ENROLLMENT_SESSION_NOT_FOUND") {
      return "声纹会话已失效，请重新开始";
    }
    if (e.error.code === "ENROLLMENT_PERSON_MISMATCH") {
      return "声纹会话人员不匹配，请重新开始";
    }
    return `${e.error.code}: ${e.error.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}

function getSafeReturnTo(returnTo: string | null): string | null {
  if (!returnTo || !returnTo.startsWith("/") || returnTo.startsWith("//")) return null;
  return returnTo;
}
