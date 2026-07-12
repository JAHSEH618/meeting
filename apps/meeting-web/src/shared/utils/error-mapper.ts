// Error-code → user message mapping.
//
// The map itself is contract-generated (error-messages.gen.ts, from
// meeting-contracts/schemas/common/error-codes.yaml) so the SSOT's
// userMessage / retryable flags reach the UI without a hand-copied table
// drifting out of sync. Keep this module as the app-facing façade.
import { errorCodeMessage, isErrorCodeRetryable } from "../api/error-messages.gen";

export function getUserMessage(code: string): string {
  return errorCodeMessage(code);
}

export function isAuthError(code: string): boolean {
  return code === "AUTH_REQUIRED" || code === "PERMISSION_DENIED";
}

export function isRetryable(code: string): boolean {
  return isErrorCodeRetryable(code);
}
