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
import { hashFileForUpload, type UploadHashPart } from "@shared/utils/upload-hasher";
import { initialUploadState, uploadReducer, type UploadPartState } from "./upload-reducer";
import { AudioUploadSummary } from "./AudioUploadSummary";
import { AudioPartList } from "./AudioPartList";
import { formatUploadStatus } from "@shared/utils/formatters";

const DEFAULT_CONCURRENCY = 3;
const MAX_PART_RETRIES = 3;
const SESSION_STORAGE_PREFIX = "meeting.audioUpload.";
const MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024;
const PART_SIZE_BYTES = 8 * 1024 * 1024;

export function AudioUploadPage() {
  const { meetingId = "" } = useParams();
  const navigate = useNavigate();
  const [state, dispatch] = useReducer(uploadReducer, initialUploadState);
  const [file, setFile] = useState<File | null>(null);
  const [concurrency, setConcurrency] = useState(DEFAULT_CONCURRENCY);
  const [message, setMessage] = useState<string | null>(null);
  const [abortController, setAbortController] = useState<AbortController | null>(null);
  const [hashProgress, setHashProgress] = useState<number | null>(null);
  const storageKey = `${SESSION_STORAGE_PREFIX}${meetingId}`;

  const onHashProgress = (bytesHashed: number, totalBytes: number) => {
    setHashProgress(totalBytes > 0 ? Math.round((bytesHashed / totalBytes) * 100) : 0);
  };

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
            /* remain on this page; user can navigate manually */
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
    const controller = new AbortController();
    setAbortController(controller);
    try {
      validateFile(file);
      // Single pass: file hash + per-part hashes from the same read, in a
      // Web Worker so the UI stays responsive on GB-scale recordings.
      setHashProgress(0);
      let hashed = await hashFileForUpload(file, PART_SIZE_BYTES, onHashProgress);
      const session = await createAudioUpload(meetingId, {
        fileName: file.name,
        contentType: file.type || "application/octet-stream",
        fileSizeBytes: file.size,
        fileSha256: hashed.fileSha256,
        partSizeBytes: PART_SIZE_BYTES,
      });
      window.localStorage.setItem(storageKey, session.uploadId);
      if (session.partSizeBytes !== PART_SIZE_BYTES) {
        // Server overrode the requested part size (rare): part hashes must
        // match its boundaries, so rerun the pass at the server's size.
        setHashProgress(0);
        hashed = await hashFileForUpload(file, session.partSizeBytes, onHashProgress);
      }
      setHashProgress(null);
      const parts = toPartStates(hashed.parts);
      dispatch({ type: "session", session, parts });
      await uploadParts(session.uploadId, parts, controller.signal);
      await finalize(session.uploadId, hashed.fileSha256, parts);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause));
      dispatch({ type: "failed", errorCode: apiError.code || "INTERNAL_ERROR" });
    } finally {
      setHashProgress(null);
      setAbortController(null);
    }
  }

  async function uploadParts(uploadId: string, parts: UploadPartState[], signal: AbortSignal) {
    let nextIndex = 0;
    async function worker() {
      while (nextIndex < parts.length) {
        if (signal.aborted) return;
        const part = parts[nextIndex];
        nextIndex += 1;
        if (!part) return;
        await uploadOnePart(uploadId, part, signal);
      }
    }
    await Promise.all(
      Array.from({ length: Math.min(concurrency, parts.length) }, () => worker()),
    );
  }

  async function uploadOnePart(uploadId: string, part: UploadPartState, signal: AbortSignal) {
    if (!file) return;
    for (let attempt = 1; attempt <= MAX_PART_RETRIES; attempt += 1) {
      if (signal.aborted) return;
      dispatch({ type: "part-start", partNumber: part.partNumber, attempts: attempt });
      try {
        const signed = await createAudioUploadPart(meetingId, uploadId, {
          partNumber: part.partNumber,
          sizeBytes: part.sizeBytes,
          partSha256: part.partSha256,
        }, signal);
        const offset = (part.partNumber - 1) * (state.session?.partSizeBytes || PART_SIZE_BYTES);
        const blob = file.slice(offset, offset + part.sizeBytes);
        const uploaded = await putAudioUploadPart(signed.uploadUrl, blob, signed.headers, signal);
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
      if (abortController) {
        abortController.abort();
      }
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
    const controller = new AbortController();
    setAbortController(controller);
    try {
      validateFile(file);
      if (file.size !== state.session.fileSizeBytes) {
        const error = new Error("文件大小与原上传会话不一致，请选择同一个文件") as ApiClientError;
        error.code = "UPLOAD_FILE_MISMATCH";
        error.retryable = false;
        throw error;
      }
      dispatch({ type: "prepare" });
      // The resume path needs the part hashes anyway, so a fresh single
      // pass yields both the fingerprint check and the part table.
      setHashProgress(0);
      const hashed = await hashFileForUpload(file, state.session.partSizeBytes, onHashProgress);
      setHashProgress(null);
      if (hashed.fileSha256 !== state.session.fileSha256) {
        const error = new Error("文件指纹与原上传会话不一致，请选择同一个文件") as ApiClientError;
        error.code = "UPLOAD_FILE_MISMATCH";
        error.retryable = false;
        throw error;
      }
      const parts = toPartStates(hashed.parts);
      reconcilePartsWithSession(parts, state.session);
      dispatch({ type: "session", session: state.session, parts });
      await uploadParts(state.session.uploadId, parts.filter((part) => part.status !== "completed"), controller.signal);
      await finalize(state.session.uploadId, hashed.fileSha256, parts);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause));
      dispatch({ type: "failed", errorCode: apiError.code || "INTERNAL_ERROR" });
    } finally {
      setHashProgress(null);
      setAbortController(null);
    }
  }

  async function finalize(uploadId: string, sha256: string, parts: UploadPartState[]) {
    dispatch({ type: "complete-start" });
    try {
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
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause));
      dispatch({ type: "failed", errorCode: apiError.code || "INTERNAL_ERROR" });
    }
  }

  return (
    <main className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">音频</span>
          <h1 className="page-hero__title">上传并处理</h1>
          <p className="page-hero__subtitle">上传完成后会自动启动处理任务</p>
        </div>
        <div className="page-hero__actions">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/transcript`}>转录</Link>
        </div>
      </header>

      {state.errorCode ? <div className="error" role="alert">{getUserMessage(state.errorCode)}</div> : null}
      {message ? <div className="error" role="alert">{message}</div> : null}

      <section className="glass-panel stack">
        <div className="field">
          <label className="field__label" htmlFor="audio-file">音频文件</label>
          <input
            id="audio-file"
            name="audioFile"
            type="file"
            accept="audio/*,.wav,.mp3,.m4a,.aac,.flac,.ogg"
            onChange={(event) => {
              const nextFile = event.target.files?.[0] ?? null;
              setFile(nextFile);
              setMessage(null);
            }}
          />
        </div>

        <AudioUploadSummary
          file={file}
          session={state.session}
          status={state.status}
        />

        <div className="field" style={{ maxWidth: 240 }}>
          <label className="field__label" htmlFor="upload-concurrency">并发数</label>
          <input
            id="upload-concurrency"
            name="concurrency"
            type="number"
            min={1}
            max={5}
            value={concurrency}
            onChange={(event) => setConcurrency(Math.max(1, Math.min(5, Number(event.target.value) || DEFAULT_CONCURRENCY)))}
          />
        </div>

        <div className="toolbar">
          <button
            className="button button--primary"
            type="button"
            disabled={!file || isBusy(state.status) || hasActiveSession(state)}
            onClick={startUpload}
          >
            {buttonText(state.status)}
          </button>
          <button
            type="button"
            className="button"
            disabled={!state.session || state.status === "completed"}
            onClick={abort}
          >
            取消上传
          </button>
          <button
            type="button"
            className="button"
            disabled={!state.session || !file || isBusy(state.status)}
            onClick={resume}
          >
            继续上传
          </button>
        </div>
      </section>

      <section className="glass-panel glass-panel--compact stack">
        <div className="toolbar">
          <strong>上传状态</strong>
          <span className="pill pill--info">{formatUploadStatus(state.status)}</span>
        </div>
        <div
          className="progress"
          role="progressbar"
          aria-label={hashProgress != null ? "文件校验进度" : "上传进度"}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={hashProgress ?? state.progress}
        >
          <span style={{ display: "block", height: "100%", width: `${hashProgress ?? state.progress}%`, background: "var(--accent)" }} />
        </div>
        {hashProgress != null ? (
          <p className="page-subtitle" aria-live="polite">正在计算文件校验和 {hashProgress}%</p>
        ) : (
          <p className="page-subtitle">已完成 {completedCount} / {state.parts.length} 个分片</p>
        )}
        <AudioPartList parts={state.parts} />
      </section>
    </main>
  );
}

function toPartStates(parts: UploadHashPart[]): UploadPartState[] {
  return parts.map((part) => ({
    partNumber: part.partNumber,
    sizeBytes: part.sizeBytes,
    partSha256: part.partSha256,
    attempts: 0,
    status: "pending" as const,
  }));
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

function isBusy(status: string): boolean {
  return status === "preparing" || status === "uploading" || status === "completing";
}

function buttonText(status: string): string {
  if (status === "preparing") return "准备中…";
  if (status === "uploading") return "上传中…";
  if (status === "completing") return "完成中…";
  if (status === "completed") return "已完成";
  return "上传并处理";
}

function hasActiveSession(state: { session: AudioUploadSession | null; status: string }): boolean {
  if (!state.session) return false;
  return state.status !== "aborted" && state.status !== "expired" && state.status !== "completed";
}
