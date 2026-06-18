import type { AudioUploadSession } from "@shared/api/types";
import { formatUploadStatus } from "@shared/utils/formatters";

interface Props {
  file: File | null;
  session: AudioUploadSession | null;
  status: string;
}

export function AudioUploadSummary({ file, session, status }: Props) {
  if (!file && !session) return null;
  return (
    <div className="upload-summary">
      {file ? (
        <>
          <div>
            <span className="page-subtitle">文件名</span>
            <strong>{file.name}</strong>
          </div>
          <div>
            <span className="page-subtitle">大小</span>
            <strong>{formatBytes(file.size)}</strong>
          </div>
          <div>
            <span className="page-subtitle">类型</span>
            <strong>{file.type || "application/octet-stream"}</strong>
          </div>
        </>
      ) : null}
      {session ? (
        <>
          <div>
            <span className="page-subtitle">上传会话</span>
            <strong>已建立</strong>
          </div>
          <div>
            <span className="page-subtitle">过期时间</span>
            <strong>{formatTime(session.expiresAt)}</strong>
          </div>
          <div>
            <span className="page-subtitle">状态</span>
            <strong>{formatUploadStatus(status)}</strong>
          </div>
        </>
      ) : null}
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString("zh-CN");
}
