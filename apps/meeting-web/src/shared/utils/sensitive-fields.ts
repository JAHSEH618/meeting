/**
 * Sanitizer that strips known internal / sensitive fields from arbitrary objects
 * before they are written to logs, analytics breadcrumbs, or rendered in the UI.
 *
 * Phase 4 invariant: speaker embedding values, model raw output, and
 * artifactManifestId are *internal* details that MUST NOT leave the API boundary
 * into client-visible surfaces. This module is the last-line safety net for
 * code paths that accidentally include them (e.g. via error.details forwarding).
 */

const FORBIDDEN_KEYS: ReadonlySet<string> = new Set([
  "values",
  "embedding",
  "speakerEmbedding",
  "embeddingValues",
  "embeddingHash",
  "wrappedDataKey",
  "wrappedDek",
  "encryptionKeyId",
  "ciphertext",
  "embeddingCiphertext",
  "artifactManifestId",
  "rawModelOutput",
  "speakerModelOutput",
]);

const MAX_DEPTH = 6;

export function sanitizeForLogging<T>(value: T, depth = 0): T {
  if (value == null || depth >= MAX_DEPTH) {
    return value;
  }
  if (typeof value !== "object") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeForLogging(item, depth + 1)) as unknown as T;
  }
  const source = value as Record<string, unknown>;
  const result: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(source)) {
    if (FORBIDDEN_KEYS.has(key)) {
      result[key] = "[REDACTED]";
      continue;
    }
    result[key] = sanitizeForLogging(child, depth + 1);
  }
  return result as T;
}

export function isForbiddenKey(key: string): boolean {
  return FORBIDDEN_KEYS.has(key);
}
