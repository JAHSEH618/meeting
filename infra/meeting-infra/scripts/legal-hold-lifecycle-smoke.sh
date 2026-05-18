#!/usr/bin/env bash
# final-check.md E1 — legal-hold lifecycle smoke test.
#
# Verifies that the meeting-api DELETE endpoint is correctly gated by
# the legal-hold subsystem (Phase 7.2 + final-check.md A2 wiring):
#
#   1. Create a fresh meeting.
#   2. Place a legal hold on it.
#   3. Try to delete it — must return 423 LEGAL_HOLD_BLOCKED.
#   4. Confirm the meeting row still exists (status != DELETED).
#   5. Release the hold.
#   6. Delete again — must return 200 with status=DELETED.
#   7. Confirm the meeting is no longer visible to list/get.
#
# Requires a running full-stack:
#   docker compose --profile full-stack \
#       -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
#
# Usage:
#   bash infra/meeting-infra/scripts/legal-hold-lifecycle-smoke.sh
#
# Exits non-zero on any assertion failure. Set LEGAL_HOLD_SMOKE_KEEP=1
# to leave the meeting + hold rows in place after the run for inspection.

set -euo pipefail

API="${MEETING_API_URL:-http://localhost:8080}"
USER_LOGIN="${SMOKE_USER:-demo@meeting.local}"
USER_PASS="${SMOKE_PASS:-demo}"
ADMIN_LOGIN="${SMOKE_ADMIN:-admin@meeting.local}"
ADMIN_PASS="${SMOKE_ADMIN_PASS:-admin}"

step() { printf '\n→ %s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

require_cmd() {
    command -v "$1" >/dev/null || die "$1 is required on PATH"
}

require_cmd curl
require_cmd jq

login() {
    local user="$1" pass="$2"
    curl -fsSL -X POST "$API/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$user\",\"password\":\"$pass\"}" \
        | jq -er '.data.accessToken // empty' \
        || die "login as $user failed — is API up at $API?"
}

step "Logging in as user + admin"
USER_TOKEN=$(login "$USER_LOGIN" "$USER_PASS")
ADMIN_TOKEN=$(login "$ADMIN_LOGIN" "$ADMIN_PASS")
ok "obtained tokens"

step "Creating a fresh meeting"
NOW=$(date -u +%Y%m%dT%H%M%SZ)
CREATE_RESP=$(curl -fsSL -X POST "$API/api/meetings" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "Content-Type: application/json" \
    -H "X-Request-Id: lh_smoke_create_$NOW" \
    -H "X-Trace-Id: lh_smoke_$NOW" \
    -H "Idempotency-Key: lh_smoke_create_$NOW" \
    -d '{"title":"legal-hold smoke","language":"zh","securityLevel":"INTERNAL"}')
MEETING_ID=$(echo "$CREATE_RESP" | jq -er '.data.meetingId // empty' \
    || die "create response missing meetingId")
ok "meeting created: $MEETING_ID"

step "Placing legal hold on the meeting (admin)"
HOLD_RESP=$(curl -fsSL -X POST "$API/admin/legal-holds" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -H "X-Request-Id: lh_smoke_place_$NOW" \
    -H "X-Trace-Id: lh_smoke_$NOW" \
    -H "Idempotency-Key: lh_smoke_place_$NOW" \
    -d "{\"scopeType\":\"MEETING\",\"scopeId\":\"$MEETING_ID\",\"reason\":\"legal-hold-smoke\"}")
HOLD_ID=$(echo "$HOLD_RESP" | jq -er '.data.holdId // .data.id // empty' \
    || die "place legal-hold response missing id")
ok "hold placed: $HOLD_ID"

step "Attempting to delete meeting under hold — expect 423"
DELETE_CODE=$(curl -o /tmp/lh-smoke-delete.json -s -w '%{http_code}' \
    -X DELETE "$API/api/meetings/$MEETING_ID" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-Request-Id: lh_smoke_del_$NOW" \
    -H "X-Trace-Id: lh_smoke_$NOW" \
    -H "Idempotency-Key: lh_smoke_del_$NOW" \
    -H "Content-Type: application/json" \
    -d '{"reason":"smoke"}')
if [[ "$DELETE_CODE" != "423" ]]; then
    die "expected 423 LEGAL_HOLD_BLOCKED, got $DELETE_CODE — body: $(cat /tmp/lh-smoke-delete.json)"
fi
DELETE_CODE_BODY=$(jq -r '.error.code // empty' /tmp/lh-smoke-delete.json)
if [[ "$DELETE_CODE_BODY" != "LEGAL_HOLD_BLOCKED" ]]; then
    die "expected error.code=LEGAL_HOLD_BLOCKED, got $DELETE_CODE_BODY"
fi
ok "delete blocked by legal hold (423 LEGAL_HOLD_BLOCKED)"

step "Confirming meeting row survives the blocked delete"
GET_RESP=$(curl -fsSL -X GET "$API/api/meetings/$MEETING_ID" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-Request-Id: lh_smoke_get1_$NOW" \
    -H "X-Trace-Id: lh_smoke_$NOW")
GET_STATUS=$(echo "$GET_RESP" | jq -r '.data.status // empty')
if [[ "$GET_STATUS" == "DELETED" || -z "$GET_STATUS" ]]; then
    die "meeting was deleted despite hold (status=$GET_STATUS) — RLS / repository bypassed?"
fi
ok "meeting still visible with status=$GET_STATUS"

step "Releasing legal hold"
curl -fsSL -X POST "$API/admin/legal-holds/$HOLD_ID/release" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -H "X-Request-Id: lh_smoke_release_$NOW" \
    -H "X-Trace-Id: lh_smoke_$NOW" \
    -H "Idempotency-Key: lh_smoke_release_$NOW" \
    -d '{"reason":"legal-hold-smoke"}' >/dev/null
ok "hold released"

step "Deleting meeting after release — expect 200"
DELETE_CODE2=$(curl -o /tmp/lh-smoke-delete2.json -s -w '%{http_code}' \
    -X DELETE "$API/api/meetings/$MEETING_ID" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-Request-Id: lh_smoke_del2_$NOW" \
    -H "X-Trace-Id: lh_smoke_$NOW" \
    -H "Idempotency-Key: lh_smoke_del2_$NOW" \
    -H "Content-Type: application/json" \
    -d '{"reason":"smoke"}')
if [[ "$DELETE_CODE2" != "200" ]]; then
    die "expected 200 after release, got $DELETE_CODE2 — body: $(cat /tmp/lh-smoke-delete2.json)"
fi
DEL_STATUS=$(jq -r '.data.status // empty' /tmp/lh-smoke-delete2.json)
if [[ "$DEL_STATUS" != "DELETED" ]]; then
    die "expected status=DELETED, got $DEL_STATUS"
fi
ok "meeting deleted (status=DELETED)"

step "Confirming meeting no longer visible to GET"
HIDDEN_CODE=$(curl -o /tmp/lh-smoke-hidden.json -s -w '%{http_code}' \
    -X GET "$API/api/meetings/$MEETING_ID" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-Request-Id: lh_smoke_get2_$NOW" \
    -H "X-Trace-Id: lh_smoke_$NOW")
if [[ "$HIDDEN_CODE" != "404" ]]; then
    die "expected 404 for deleted meeting GET, got $HIDDEN_CODE — body: $(cat /tmp/lh-smoke-hidden.json)"
fi
ok "deleted meeting hidden from GET (404)"

if [[ "${LEGAL_HOLD_SMOKE_KEEP:-0}" != "1" ]]; then
    rm -f /tmp/lh-smoke-delete.json /tmp/lh-smoke-delete2.json /tmp/lh-smoke-hidden.json
fi

step "Smoke passed — legal hold ↔ delete contract intact"
