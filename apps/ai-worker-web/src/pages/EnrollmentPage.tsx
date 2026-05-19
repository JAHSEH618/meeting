import { useState } from "react";
import {
  commitEnrollment,
  createEnrollmentSession,
  previewEnrollment,
  searchPersons,
  uploadEnrollmentAudio,
} from "@/shared/api/endpoints";
import type { EnrollmentSessionDTO, PersonDTO } from "@/shared/api/types";
import { ApiError } from "@/shared/api/client";

const QUALITY_THRESHOLD = 0.5;

export function EnrollmentPage() {
  const [persons, setPersons] = useState<PersonDTO[]>([]);
  const [personId, setPersonId] = useState<string | null>(null);
  const [session, setSession] = useState<EnrollmentSessionDTO | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleSearch = async (q: string) => {
    setError(null);
    try {
      const result = await searchPersons(q);
      setPersons(result);
    } catch (e) {
      setError(formatError(e));
    }
  };

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

  return (
    <div>
      <h1>声纹录入</h1>
      <div className="card">
        <h2>1. 选择人员</h2>
        <input
          className="input"
          placeholder="按姓名 / 邮箱搜索…"
          onChange={(e) => void handleSearch(e.target.value)}
          aria-label="搜索人员"
        />
        <ul>
          {persons.map((p) => (
            <li key={p.id}>
              <label>
                <input
                  type="radio"
                  name="person"
                  value={p.id}
                  checked={personId === p.id}
                  onChange={() => setPersonId(p.id)}
                />
                {p.displayName} {p.email ? `(${p.email})` : ""}
              </label>
            </li>
          ))}
        </ul>
      </div>

      <div className="card">
        <h2>2. 录制 / 上传音频</h2>
        <button className="button" onClick={() => void handleStart()} disabled={!personId || busy || !!session}>
          创建录入会话
        </button>
        {session && <p data-testid="session-id">会话: {session.sessionId} · 状态: {session.state}</p>}
        <input
          type="file"
          accept="audio/*"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          disabled={!session}
        />
        <button
          className="button"
          onClick={() => void handleUploadAndPreview()}
          disabled={!session || !file || busy}
        >
          上传并预览
        </button>
      </div>

      <div className="card">
        <h2>3. 预览质量</h2>
        {session?.qualityScore !== undefined && (
          <p data-testid="quality-score">
            质量分: {session.qualityScore.toFixed(2)}
            {session.qualityScore < QUALITY_THRESHOLD && <span className="error"> ⚠️ 分数偏低，建议重录</span>}
          </p>
        )}
        <button
          className="button"
          onClick={() => void handleCommit()}
          disabled={!session || session.state !== "PREVIEWED" || busy}
        >
          确认录入
        </button>
      </div>

      {error && <div className="error" role="alert">{error}</div>}
    </div>
  );
}

function formatError(e: unknown): string {
  if (e instanceof ApiError) {
    return `${e.error.code}: ${e.error.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}
