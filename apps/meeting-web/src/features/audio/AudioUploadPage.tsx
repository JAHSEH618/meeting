import { useEffect, useMemo, useReducer, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  abortAudioUpload,
  completeAudioUpload,
  createAudioUpload,
  createAudioUploadPart,
  getAudioUpload,
  getLatestMeetingTask,
  putAudioUploadPart,
} from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import type { AudioUploadSession } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import { sha256Hex } from "@shared/utils/sha256-stream";
import { initialUploadState, uploadReducer, type UploadPartState } from "./upload-reducer";

const DEFAULT_CONCURRENCY = 3;
const MAX_PART_RETRIES = 3;
const SESSION_STORAGE_PREFIX = "meeting.audioUpload.";
// Single-PUT mode cap — the server-side `MAX_SINGLE_PUT_BYTES` in
// `AudioUploadApplicationService` is Integer.MAX_VALUE (≈ 2 GiB). Reject
// here so the user gets a clear local error before we spend minutes
// hashing a file that the API would refuse anyway.
const MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024;

export function AudioUploadPage() {
  const { meetingId = "" } = useParams();
  const navigate = useNavigate();
  const [state, dispatch] = useReducer(uploadReducer, initialUploadState);
  const [file, setFile] = useState<File | null>(null);
  const [concurrency, setConcurrency] = useState(DEFAULT_CONCURRENCY);
  const [fileSha256, setFileSha256] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const storageKey = `${SESSION_STORAGE_PREFIX}${meetingId}`;

  const completedCount = useMemo(
    () => state.parts.filter((part) => part.status === "completed").length,
    [state.parts],
  );

  useEffect(() => {
    if (!meetingId) return;
    const uploadId = window.localStorage.getItem(storageKey);
    if (!uploadId) return;
    let cancelled = false;
    getAudioUpload(meetingId, uploadId)
      .then(async (session) => {
        if (cancelled) return;
        if (session.uploadStatus === "EXPIRED") {
          dispatch({ type: "expired" });
          return;
        }
        if (session.uploadStatus === "COMPLETED") {
          window.localStorage.removeItem(storageKey);
          dispatch({ type: "session-restored", session });
          try {
            const task = await getLatestMeetingTask(meetingId);
            if (!cancelled) navigate(`/meetings/${meetingId}/tasks/${task.taskId}`, { replace: true });
          } catch {
            // remain on this page; user can navigate manually
          }
          return;
        }
        dispatch({ type: "session-restored", session });
      })
      .catch(() => window.localStorage.removeItem(storageKey));
    return () => {
      cancelled = true;
    };
  }, [meetingId, storageKey, navigate]);

  async function startUpload() {
    if (!file || !meetingId) return;
    dispatch({ type: "prepare" });
    setMessage(null);
    try {
      validateFile(file);
      const sha256 = await sha256Hex(file);
      setFileSha256(sha256);
      const session = await createAudioUpload(meetingId, {
        fileName: file.name,
        contentType: file.type || "application/octet-stream",
        fileSizeBytes: file.size,
        fileSha256: sha256,
        partSizeBytes: 8 * 1024 * 1024,
      });
      window.localStorage.setItem(storageKey, session.uploadId);
      const parts = await buildParts(file, session.partSizeBytes, sha256);
      dispatch({ type: "session", session, parts });
      await uploadParts(session.uploadId, parts);
      await finalize(session.uploadId, sha256, parts);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause));
      dispatch({ type: "failed", errorCode: apiError.code || "INTERNAL_ERROR" });
    }
  }

  async function uploadParts(uploadId: string, parts: UploadPartState[]) {
    let nextIndex = 0;
    async function worker() {
      while (nextIndex < parts.length) {
        const part = parts[nextIndex];
        nextIndex += 1;
        if (!part) return;
        await uploadOnePart(uploadId, part);
      }
    }
    await Promise.all(
      Array.from({ length: Math.min(concurrency, parts.length) }, () => worker()),
    );
  }

  async function uploadOnePart(uploadId: string, part: UploadPartState) {
    if (!file) return;
    for (let attempt = 1; attempt <= MAX_PART_RETRIES; attempt += 1) {
      dispatch({ type: "part-start", partNumber: part.partNumber, attempts: attempt });
      try {
        const signed = await createAudioUploadPart(meetingId, uploadId, {
          partNumber: part.partNumber,
          sizeBytes: part.sizeBytes,
          partSha256: part.partSha256,
        });
        const offset = (part.partNumber - 1) * (state.session?.partSizeBytes || 8 * 1024 * 1024);
        const blob = file.slice(offset, offset + part.sizeBytes);
        const uploaded = await putAudioUploadPart(signed.uploadUrl, blob, signed.headers);
        const etag = uploaded.etag || signed.etag || `etag_${part.partNumber}`;
        part.etag = etag;
        part.status = "completed";
        dispatch({ type: "part-complete", partNumber: part.partNumber, etag });
        return;
      } catch (cause) {
        if (attempt === MAX_PART_RETRIES) {
          const apiError = cause as ApiClientError;
          dispatch({ type: "part-failed", partNumber: part.partNumber, errorCode: apiError.code || "OSS_WRITE_FAILED" });
          throw cause;
        }
      }
    }
  }

  async function abort() {
    if (!meetingId || !state.session) return;
    try {
      await abortAudioUpload(meetingId, state.session.uploadId);
      window.localStorage.removeItem(storageKey);
      dispatch({ type: "aborted" });
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause));
      dispatch({ type: "failed", errorCode: apiError.code || "INTERNAL_ERROR" });
    }
  }

  async function resume() {
    if (!meetingId || !state.session || !file) return;
    setMessage(null);
    try {
      validateFile(file);
      if (file.size !== state.session.fileSizeBytes) {
        const error = new Error("文件大小与原上传会话不一致，请选择同一个文件") as ApiClientError;
        error.code = "UPLOAD_FILE_MISMATCH";
        error.retryable = false;
        throw error;
      }
      dispatch({ type: "prepare" });
      const sha256 = fileSha256 ?? await sha256Hex(file);
      if (sha256 !== state.session.fileSha256) {
        const error = new Error("文件指纹与原上传会话不一致，请选择同一个文件") as ApiClientError;
        error.code = "UPLOAD_FILE_MISMATCH";
        error.retryable = false;
        throw error;
      }
      setFileSha256(sha256);
      const parts = await buildParts(file, state.session.partSizeBytes, sha256);
      reconcilePartsWithSession(parts, state.session);
      dispatch({ type: "session", session: state.session, parts });
      await uploadParts(state.session.uploadId, parts.filter((part) => part.status !== "completed"));
      await finalize(state.session.uploadId, sha256, parts);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause));
      dispatch({ type: "failed", errorCode: apiError.code || "INTERNAL_ERROR" });
    }
  }

  async function finalize(uploadId: string, sha256: string, parts: UploadPartState[]) {
    dispatch({ type: "complete-start" });
    const completed = await completeAudioUpload(meetingId, uploadId, {
      fileSha256: sha256,
      durationMs: null,
      parts: parts.map((part) => ({
        partNumber: part.partNumber,
        partSha256: part.partSha256,
        etag: part.etag || "",
      })),
    });
    dispatch({ type: "completed", session: completed });
    window.localStorage.removeItem(storageKey);
    const task = await getLatestMeetingTask(meetingId);
    navigate(`/meetings/${meetingId}/tasks/${task.taskId}`);
  }

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">音频上传</h1>
          <p className="muted">{meetingId}</p>
        </div>
        <div className="toolbar">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/transcript`}>转录</Link>
        </div>
      </div>

      {state.errorCode ? <div className="error" role="alert">{getUserMessage(state.errorCode)}</div> : null}
      {message ? <div className="error" role="alert">{message}</div> : null}

      <section className="card stack">
        <div className="field">
          <label htmlFor="audio-file">音频文件</label>
          <input
            id="audio-file"
            type="file"
            accept="audio/*,.wav,.mp3,.m4a,.aac,.flac,.ogg"
            onChange={(event) => {
              const nextFile = event.target.files?.[0] ?? null;
              setFile(nextFile);
              setFileSha256(null);
              setMessage(null);
            }}
          />
        </div>

        {file ? (
          <div className="upload-summary">
            <div><span className="muted">文件名</span><strong>{file.name}</strong></div>
            <div><span className="muted">大小</span><strong>{formatBytes(file.size)}</strong></div>
            <div><span className="muted">类型</span><strong>{file.type || "application/octet-stream"}</strong></div>
          </div>
        ) : null}

        <div className="field" style={{ maxWidth: 240 }}>
          <label htmlFor="upload-concurrency">并发数</label>
          <input
            id="upload-concurrency"
            type="number"
            min={1}
            max={5}
            value={concurrency}
            onChange={(event) => setConcurrency(Math.max(1, Math.min(5, Number(event.target.value) || DEFAULT_CONCURRENCY)))}
          />
        </div>

        <div className="toolbar">
          <button className="primary" type="button" disabled={!file || isBusy(state.status) || hasActiveSession(state)} onClick={startUpload}>
            {buttonText(state.status)}
          </button>
          <button type="button" disabled={!state.session || state.status === "completed"} onClick={abort}>取消上传</button>
          <button type="button" disabled={!state.session || !file || isBusy(state.status)} onClick={resume}>继续上传</button>
        </div>
      </section>

      <section className="card stack">
        <div className="toolbar">
          <strong>上传状态</strong>
          <span className="badge">{state.status}</span>
          {state.session ? <span className="muted">{state.session.uploadId}</span> : null}
          {state.session ? <span className="muted">过期时间 {formatTime(state.session.expiresAt)}</span> : null}
        </div>
        <div className="progress-bar"><span style={{ width: `${state.progress}%` }} /></div>
        <p className="muted">{completedCount} / {state.parts.length} parts</p>
        {state.parts.length > 0 ? (
          <div className="part-grid">
            {state.parts.map((part) => (
              <div className="part-tile" key={part.partNumber}>
                <strong>#{part.partNumber}</strong>
                <span className="badge">{part.status}</span>
                <span className="muted">{formatBytes(part.sizeBytes)}</span>
                <span className="muted">retry {part.attempts}</span>
              </div>
            ))}
          </div>
        ) : null}
      </section>
    </main>
  );
}

async function buildParts(
  file: File,
  partSizeBytes: number,
  fileSha256: string,
): Promise<UploadPartState[]> {
  const count = Math.ceil(file.size / partSizeBytes);
  // Single-PUT mode: when the server has coerced partSize >= fileSize the
  // only part is the full file, so reuse the already-computed fileSha256
  // instead of streaming the file a second time.
  if (count === 1) {
    return [
      {
        partNumber: 1,
        sizeBytes: file.size,
        partSha256: fileSha256,
        attempts: 0,
        status: "pending",
      },
    ];
  }
  const parts: UploadPartState[] = [];
  for (let index = 0; index < count; index += 1) {
    const start = index * partSizeBytes;
    const end = Math.min(file.size, start + partSizeBytes);
    const blob = file.slice(start, end);
    parts.push({
      partNumber: index + 1,
      sizeBytes: blob.size,
      partSha256: await sha256Hex(blob),
      attempts: 0,
      status: "pending",
    });
  }
  return parts;
}

function reconcilePartsWithSession(parts: UploadPartState[], session: AudioUploadSession) {
  for (const part of parts) {
    const remote = session.parts.find((item) => item.partNumber === part.partNumber);
    if (!remote) continue;
    if (remote.uploadStatus === "COMPLETED" && remote.partSha256 === part.partSha256) {
      part.status = "completed";
      part.etag = remote.etag ?? part.etag;
    }
  }
}

function validateFile(file: File) {
  const supported = file.type.startsWith("audio/") || /\.(wav|mp3|m4a|aac|flac|ogg)$/i.test(file.name);
  if (!supported) {
    const error = new Error("音频格式不支持") as ApiClientError;
    error.code = "AUDIO_UNSUPPORTED_FORMAT";
    error.retryable = false;
    throw error;
  }
  if (file.size <= 0) {
    const error = new Error("音频文件为空") as ApiClientError;
    error.code = "VALIDATION_FAILED";
    error.retryable = false;
    throw error;
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    const error = new Error("音频文件超过 2 GiB 单 PUT 上限") as ApiClientError;
    error.code = "VALIDATION_FAILED";
    error.retryable = false;
    throw error;
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString();
}

function isBusy(status: string): boolean {
  return status === "preparing" || status === "uploading" || status === "completing";
}

function buttonText(status: string): string {
  if (status === "preparing") return "准备中";
  if (status === "uploading") return "上传中";
  if (status === "completing") return "完成中";
  if (status === "completed") return "已完成";
  return "开始上传";
}

function hasActiveSession(state: { session: AudioUploadSession | null; status: string }): boolean {
  if (!state.session) return false;
  return state.status !== "aborted" && state.status !== "expired" && state.status !== "completed";
}
