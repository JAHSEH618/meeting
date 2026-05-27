import type { UploadPartState } from "./upload-reducer";

interface Props {
  parts: UploadPartState[];
}

const PART_STATUS_PILL: Record<string, string> = {
  pending: "pill--neutral",
  uploading: "pill--info",
  completed: "pill--success",
  failed: "pill--danger",
};

export function AudioPartList({ parts }: Props) {
  if (parts.length === 0) return null;
  return (
    <div className="part-grid" aria-label="upload-parts">
      {parts.map((part) => (
        <div className="part-tile" key={part.partNumber}>
          <strong>#{part.partNumber}</strong>
          <span className={`pill ${PART_STATUS_PILL[part.status] ?? "pill--neutral"}`}>
            {part.status}
          </span>
          <span className="page-subtitle">{formatBytes(part.sizeBytes)}</span>
          <span className="page-subtitle">retry {part.attempts}</span>
        </div>
      ))}
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
