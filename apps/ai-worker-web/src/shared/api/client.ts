/**
 * Thin fetch wrapper:
 *   - injects Authorization, X-Request-Id, X-Trace-Id (+ Idempotency-Key on writes)
 *   - unwraps the unified envelope {success, data, error, requestId, traceId}
 *   - throws {@link ApiError} on non-success
 *   - on 401 clears auth + redirects to Java login
 */

import { authStore, redirectToLogin } from "@/shared/auth/store";

export interface ErrorInfo {
  code: string;
  message: string;
  retryable: boolean;
  details?: Record<string, unknown>;
}

export class ApiError extends Error {
  public readonly status: number;
  public readonly error: ErrorInfo;
  public readonly requestId: string;
  public readonly traceId: string;

  constructor(status: number, error: ErrorInfo, requestId: string, traceId: string) {
    super(error.message);
    this.status = status;
    this.error = error;
    this.requestId = requestId;
    this.traceId = traceId;
  }
}

function uuid(): string {
  // Browser-safe UUID v4 (works in jsdom + every modern browser; falls back to
  // pseudo-random where crypto isn't available — e.g. some Playwright contexts).
  const cryptoApi = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export interface ApiCallOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  /** Required for write methods; ignored on GET. */
  idempotencyKey?: string;
  rawResponse?: boolean;
  signal?: AbortSignal;
}

/** Mainline fetch call. Returns ``data`` from the envelope, or throws ApiError. */
export async function apiCall<T = unknown>(path: string, options: ApiCallOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Request-Id": uuid(),
    "X-Trace-Id": uuid(),
    Accept: "application/json",
  };
  const token = authStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (method !== "GET" && method !== "DELETE") {
    headers["Idempotency-Key"] = options.idempotencyKey ?? uuid();
  }
  const search = options.query
    ? "?" + new URLSearchParams(
        Object.entries(options.query)
          .filter(([, v]) => v !== undefined && v !== null && v !== "")
          .map(([k, v]) => [k, String(v)]),
      ).toString()
    : "";
  const response = await fetch(path + search, {
    method,
    headers,
    body: method === "GET" ? undefined : options.body == null ? null : JSON.stringify(options.body),
    credentials: "include",
    signal: options.signal,
  });
  if (response.status === 401) {
    authStore.clear();
    redirectToLogin();
    throw new ApiError(401, { code: "UNAUTHENTICATED", message: "session expired", retryable: false }, "", "");
  }
  if (options.rawResponse) {
    return response as unknown as T;
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError(response.status, {
      code: "UPSTREAM_INVALID_RESPONSE",
      message: `non-JSON ${response.status} response`,
      retryable: response.status >= 500,
    }, "", "");
  }
  const envelope = payload as { success: boolean; data?: T; error?: ErrorInfo; requestId?: string; traceId?: string };
  if (!response.ok || envelope.success === false || envelope.error) {
    const info: ErrorInfo = envelope.error ?? {
      code: "UNKNOWN_ERROR",
      message: `request failed with status ${response.status}`,
      retryable: response.status >= 500,
    };
    throw new ApiError(response.status, info, envelope.requestId ?? "", envelope.traceId ?? "");
  }
  return envelope.data as T;
}

/** Upload raw bytes (PUT) to a path; used by /admin/enrollment/sessions/{id}/audio. */
export async function apiUpload(
  path: string,
  body: Blob | ArrayBuffer,
  options: { idempotencyKey?: string } = {},
): Promise<unknown> {
  const headers: Record<string, string> = {
    "X-Request-Id": uuid(),
    "X-Trace-Id": uuid(),
    "Idempotency-Key": options.idempotencyKey ?? uuid(),
    "Content-Type": "application/octet-stream",
  };
  const token = authStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(path, { method: "PUT", headers, body, credentials: "include" });
  if (response.status === 401) {
    authStore.clear();
    redirectToLogin();
    throw new ApiError(401, { code: "UNAUTHENTICATED", message: "session expired", retryable: false }, "", "");
  }
  const payload = (await response.json()) as { success: boolean; data?: unknown; error?: ErrorInfo };
  if (!response.ok || payload.success === false || payload.error) {
    throw new ApiError(
      response.status,
      payload.error ?? { code: "UPLOAD_FAILED", message: "audio upload failed", retryable: true },
      "",
      "",
    );
  }
  return payload.data;
}
