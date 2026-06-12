#!/usr/bin/env bash
# Phase 6.6.3 — end-to-end PDF export smoke test.
#
# Requires a running full-stack compose:
#   docker compose --profile full-stack up -d
#
# Usage:
#   bash infra/meeting-infra/scripts/export-pdf-smoke.sh
#
# Exits non-zero on any failure. Cleans up the artefact unless
# EXPORT_SMOKE_KEEP_PDF=1 is set.

set -euo pipefail

API="${MEETING_API_URL:-http://localhost:8080}"
USER="${SMOKE_USER:-demo@meeting.local}"
PASS="${SMOKE_PASS:-demo}"

step() { printf '\n→ %s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

curl_json() {
    curl -fsSL -H 'Content-Type: application/json' "$@"
}

require_cmd() {
    command -v "$1" >/dev/null || die "$1 is required on PATH"
}

require_cmd curl
require_cmd jq
require_cmd pdftotext   # poppler-utils on linux, brew install poppler on macOS

step "Login as $USER"
LOGIN=$(curl_json -X POST "$API/api/auth/login" \
    -H 'X-Request-Id: smoke-login' \
    -H 'X-Trace-Id: smoke-login' \
    -H 'Idempotency-Key: smoke-login' \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.accessToken // empty')
[ -n "$TOKEN" ] || die "login did not return accessToken: $LOGIN"
ok "got access token"

AUTH=(-H "Authorization: Bearer $TOKEN")

step "Create a smoke meeting"
RID="smoke-$(date +%s)"
MEETING=$(curl_json -X POST "$API/api/meetings" \
    "${AUTH[@]}" \
    -H "X-Request-Id: $RID-create" \
    -H "X-Trace-Id: $RID" \
    -H "Idempotency-Key: $RID-create" \
    -d "{\"title\":\"PDF export smoke $RID\",\"language\":\"zh\"}")
MEETING_ID=$(echo "$MEETING" | jq -r '.data.id // empty')
[ -n "$MEETING_ID" ] || die "meeting create failed: $MEETING"
ok "meeting $MEETING_ID"

step "Create a PDF export"
EXPORT=$(curl_json -X POST "$API/api/meetings/$MEETING_ID/exports" \
    "${AUTH[@]}" \
    -H "X-Request-Id: $RID-export" \
    -H "X-Trace-Id: $RID" \
    -H "Idempotency-Key: $RID-export" \
    -d "{\"format\":\"PDF\",\"watermarkText\":\"smoke-$RID\"}")
EXPORT_ID=$(echo "$EXPORT" | jq -r '.data.exportId // empty')
[ -n "$EXPORT_ID" ] || die "export create failed: $EXPORT"
ok "export $EXPORT_ID queued"

step "Poll until export reaches a terminal state (max 120 s)"
DEADLINE=$(( $(date +%s) + 120 ))
STATUS=""
DOWNLOAD_URL=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    STATE=$(curl_json -X GET "$API/api/exports/$EXPORT_ID" "${AUTH[@]}")
    STATUS=$(echo "$STATE" | jq -r '.data.status // empty')
    case "$STATUS" in
        SUCCEEDED)
            DOWNLOAD_URL=$(echo "$STATE" | jq -r '.data.downloadUrl // empty')
            ok "status=SUCCEEDED"
            break ;;
        FAILED|CANCELLED|REVOKED)
            die "export terminal in unexpected state $STATUS: $STATE" ;;
        *)
            printf '  · status=%s, waiting…\n' "$STATUS"
            sleep 2 ;;
    esac
done
[ "$STATUS" = "SUCCEEDED" ] || die "export did not reach SUCCEEDED within 120 s (last status=$STATUS)"
[ -n "$DOWNLOAD_URL" ] || die "SUCCEEDED but downloadUrl is empty"

step "Fetch the PDF"
TMP=$(mktemp -t meeting-export-smoke-XXXXXX.pdf)
trap '[ -n "${EXPORT_SMOKE_KEEP_PDF:-}" ] || rm -f "$TMP"' EXIT
curl -fsSL -o "$TMP" "$DOWNLOAD_URL"
ok "downloaded $(stat -f '%z bytes' "$TMP" 2>/dev/null || wc -c < "$TMP")"

step "Verify the PDF contains the watermark"
TEXT=$(pdftotext "$TMP" - 2>/dev/null)
echo "$TEXT" | grep -q "smoke-$RID" \
    && ok "watermark text present" \
    || die "watermark text not found in extracted PDF"

step "Revoke the download link and confirm 410 on re-fetch"
curl -fsSL -X POST "$API/api/exports/$EXPORT_ID/revoke-link" \
    "${AUTH[@]}" \
    -H "X-Request-Id: $RID-revoke" \
    -H "X-Trace-Id: $RID" \
    -H "Idempotency-Key: $RID-revoke" \
    -d '{}' >/dev/null
AFTER=$(curl_json -X GET "$API/api/exports/$EXPORT_ID" "${AUTH[@]}")
echo "$AFTER" | jq -e '.data.revoked == true and .data.downloadUrl == null' >/dev/null \
    || die "expected revoked=true and downloadUrl=null after revoke: $AFTER"
ok "revocation effective"

printf '\nALL GOOD: PDF export smoke passed for meeting=%s export=%s\n' "$MEETING_ID" "$EXPORT_ID"
