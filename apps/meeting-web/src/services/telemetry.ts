/**
 * Minimal telemetry surface for the meeting-web SPA.
 *
 * Goals (spec 8.3.3):
 * - Allow components to report errors / structured events
 *   without committing to a specific upstream provider (Sentry,
 *   in-house collector, console).
 * - Strip anything that even looks like a secret or meeting content
 *   before it leaves the browser. The redact list is conservative —
 *   anything novel goes through {@link safeContext}.
 *
 * Wire `setTelemetrySink` in `main.tsx` once the upstream provider is
 * decided; tests can swap in a recording sink.
 */
export interface TelemetryEvent {
  /** Free-form event name, e.g. {@code "rag_query_failed"}. */
  name: string;
  /** Optional route or feature scope. */
  route?: string;
  /** Free-form structured context, will be redacted before send. */
  context?: Record<string, unknown>;
  /** Optional caught error; redacts message + stack. */
  error?: unknown;
}

export interface TelemetrySink {
  report(event: TelemetryEvent): void;
}

const SENSITIVE_KEYS = new Set<string>([
  "authorization",
  "auth",
  "token",
  "accesstoken",
  "refreshtoken",
  "password",
  "secret",
  "apikey",
  "hmac",
  "transcript",
  "text",
  "content",
  "body",
  "payload",
  "filename",
  "embedding",
  "embeddings",
]);

const SAFE_CONTEXT_ALLOWLIST = new Set<string>([
  "route",
  "errorcode",
  "requestid",
  "traceid",
  "status",
  "method",
  "browser",
  "os",
  "duration",
  "retrycount",
  "feature",
]);

let sink: TelemetrySink = {
  report(event) {
    // Default sink: log a redacted copy to console so we still see
    // errors during dev without leaking sensitive payload data.
    // eslint-disable-next-line no-console
    console.warn("[telemetry]", redactEvent(event));
  },
};

export function setTelemetrySink(next: TelemetrySink): void {
  sink = next;
}

export function reportError(error: unknown, context?: Record<string, unknown>): void {
  sink.report(redactEvent({
    name: "client_error",
    context,
    error,
  }));
}

export function reportEvent(name: string, context?: Record<string, unknown>): void {
  sink.report(redactEvent({ name, context }));
}

/**
 * Strip the parts of an event that would leak meeting content,
 * credentials, or arbitrary user data. The implementation is
 * intentionally allowlist-driven for the {@code context} object so
 * future fields don't silently start shipping payloads.
 *
 * Exported for the unit test.
 */
export function redactEvent(event: TelemetryEvent): TelemetryEvent {
  const ctx = event.context ?? {};
  const safe: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(ctx)) {
    const lowered = key.toLowerCase();
    if (SENSITIVE_KEYS.has(lowered)) {
      continue;
    }
    if (SAFE_CONTEXT_ALLOWLIST.has(lowered)) {
      safe[key] = safeScalar(value);
    }
  }
  let errorSummary: { message: string; name?: string } | undefined;
  if (event.error instanceof Error) {
    errorSummary = {
      message: truncate(event.error.message, 200),
      name: event.error.name,
    };
  } else if (typeof event.error === "string") {
    errorSummary = { message: truncate(event.error, 200) };
  }
  return {
    name: event.name,
    route: event.route ?? safeRoute(),
    context: safe,
    ...(errorSummary ? { error: errorSummary } : {}),
  };
}

/**
 * Drop any value that isn't a primitive — keeps the report shape
 * predictable and prevents inadvertent leakage of nested user data.
 */
function safeScalar(value: unknown): unknown {
  switch (typeof value) {
    case "string":
      return truncate(value, 200);
    case "number":
    case "boolean":
      return value;
    default:
      return null;
  }
}

function truncate(value: string, max: number): string {
  return value.length <= max ? value : value.slice(0, max) + "…";
}

function safeRoute(): string | undefined {
  if (typeof window === "undefined") return undefined;
  // Only the pathname + first query param key — never the full query
  // string (which can contain meeting / document ids in some flows).
  return window.location.pathname;
}
