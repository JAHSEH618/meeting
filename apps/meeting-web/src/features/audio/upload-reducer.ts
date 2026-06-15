import type { AudioUploadSession } from "@shared/api/types";

export type UploadUiStatus =
  | "idle"
  | "preparing"
  | "uploading"
  | "completing"
  | "completed"
  | "failed"
  | "aborted"
  | "expired";

export interface UploadPartState {
  partNumber: number;
  sizeBytes: number;
  partSha256: string;
  etag?: string | null;
  attempts: number;
  status: "pending" | "signing" | "uploading" | "completed" | "failed";
}

export interface UploadState {
  status: UploadUiStatus;
  progress: number;
  errorCode: string | null;
  session: AudioUploadSession | null;
  parts: UploadPartState[];
}

export type UploadAction =
  | { type: "prepare" }
  | { type: "session"; session: AudioUploadSession; parts?: UploadPartState[] }
  | { type: "session-restored"; session: AudioUploadSession }
  | { type: "part-start"; partNumber: number; attempts: number }
  | { type: "part-complete"; partNumber: number; etag: string }
  | { type: "part-failed"; partNumber: number; errorCode: string }
  | { type: "complete-start" }
  | { type: "completed"; session: AudioUploadSession }
  | { type: "aborted" }
  | { type: "expired" }
  | { type: "failed"; errorCode: string }
  | { type: "reset" };

export const initialUploadState: UploadState = {
  status: "idle",
  progress: 0,
  errorCode: null,
  session: null,
  parts: [],
};

export function uploadReducer(state: UploadState, action: UploadAction): UploadState {
  switch (action.type) {
    case "prepare":
      return { ...state, status: "preparing", errorCode: null, progress: 0 };
    case "session":
      return {
        ...state,
        status: action.session.uploadStatus === "COMPLETED" ? "completed" : "uploading",
        session: action.session,
        parts: action.parts ?? mergeServerParts(state.parts, action.session),
        errorCode: null,
        progress: progressForParts(action.parts ?? mergeServerParts(state.parts, action.session)),
      };
    case "session-restored": {
      const parts = mergeServerParts(state.parts, action.session);
      const status: UploadUiStatus = action.session.uploadStatus === "COMPLETED"
        ? "completed"
        : action.session.uploadStatus === "ABORTED"
          ? "aborted"
          : "idle";
      return {
        ...state,
        status,
        session: action.session,
        parts,
        errorCode: null,
        progress: progressForParts(parts),
      };
    }
    case "part-start":
      // Prevent part-start from resurrecting terminal states
      if (state.status === "completed" || state.status === "failed" || state.status === "aborted") {
        return state;
      }
      return {
        ...state,
        status: "uploading",
        parts: state.parts.map((part) =>
          part.partNumber === action.partNumber
            ? { ...part, status: "uploading", attempts: action.attempts }
            : part,
        ),
      };
    case "part-complete": {
      const parts = state.parts.map((part) =>
        part.partNumber === action.partNumber
          ? { ...part, status: "completed" as const, etag: action.etag }
          : part,
      );
      return { ...state, parts, progress: progressForParts(parts), errorCode: null };
    }
    case "part-failed":
      return {
        ...state,
        status: "failed",
        errorCode: action.errorCode,
        parts: state.parts.map((part) =>
          part.partNumber === action.partNumber ? { ...part, status: "failed" } : part,
        ),
      };
    case "complete-start":
      return { ...state, status: "completing", errorCode: null };
    case "completed":
      return { ...state, status: "completed", session: action.session, progress: 100, errorCode: null };
    case "aborted":
      return { ...state, status: "aborted", errorCode: null };
    case "expired":
      return { ...state, status: "expired", errorCode: "UPLOAD_SESSION_EXPIRED" };
    case "failed":
      return { ...state, status: "failed", errorCode: action.errorCode };
    case "reset":
      return initialUploadState;
    default:
      return state;
  }
}

function mergeServerParts(parts: UploadPartState[], session: AudioUploadSession): UploadPartState[] {
  if (parts.length > 0) {
    return parts.map((part) => {
      const serverPart = session.parts.find((item) => item.partNumber === part.partNumber);
      if (!serverPart) return part;
      return {
        ...part,
        partSha256: serverPart.partSha256,
        etag: serverPart.etag,
        status: serverPart.uploadStatus === "COMPLETED" ? "completed" : part.status,
      };
    });
  }
  return session.parts.map((part) => ({
    partNumber: part.partNumber,
    sizeBytes: part.sizeBytes,
    partSha256: part.partSha256,
    etag: part.etag,
    attempts: 0,
    status: part.uploadStatus === "COMPLETED" ? "completed" : "pending",
  }));
}

function progressForParts(parts: UploadPartState[]): number {
  if (parts.length === 0) return 0;
  return Math.round((parts.filter((part) => part.status === "completed").length / parts.length) * 100);
}
