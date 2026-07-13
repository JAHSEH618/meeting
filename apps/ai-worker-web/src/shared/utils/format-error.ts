// Single error formatter for the workstation UI.
//
// Replaces five per-page copies that showed operators the raw backend code
// (`VERSION_CONFLICT: optimistic lock failed`). Messages come from the
// contract-generated map (error-messages.gen.ts ← error-codes.yaml), so the
// SSOT's Chinese userMessage reaches the console without hand-copied tables.
import { ApiError } from "../api/client";
import { errorCodeMessage } from "../api/error-messages.gen";
import { MultipartUploadError } from "../upload/MultipartUploader";

export function formatError(e: unknown): string {
  if (e instanceof ApiError) {
    const mapped = errorCodeMessage(e.error.code);
    // Unknown code: fall back to code + backend message so nothing is hidden.
    return mapped === e.error.code ? `${e.error.code}: ${e.error.message}` : mapped;
  }
  if (e instanceof MultipartUploadError) {
    const mapped = errorCodeMessage(e.code);
    return mapped === e.code ? `${e.code}: ${e.message}` : mapped;
  }
  return e instanceof Error ? e.message : String(e);
}
