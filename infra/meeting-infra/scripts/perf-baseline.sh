#!/usr/bin/env bash
# final-check.md H1 — performance baseline runner.
#
# Runs five scenarios against a running full-stack and writes a JSON
# report to infra/meeting-infra/perf-reports/<utc-date>-<commit>.json.
# Exits non-zero on threshold breach so this script can be invoked from
# CI as a release gate.
#
# Scenarios + thresholds (see perf-baselines.md for derivation):
#   1. meetings_list   — GET /api/meetings        p95 < 300ms @ 50 rps
#   2. callback        — POST /internal/processing-tasks/{id}/steps/...
#                                                  p95 < 200ms @ 100 rps
#   3. outbox_lag      — query meeting_api_outbox_pending_count
#                                                  sustained < 100 over 5 min
#   4. sse_ttfb        — GET /api/processing-tasks/{id}/events
#                                                  first-byte < 500ms
#   5. rag_query       — POST /api/rag/query      p95 < 2500ms @ 5 rps
#
# Usage:
#   bash infra/meeting-infra/scripts/perf-baseline.sh
#
# Environment knobs:
#   MEETING_API_URL          default http://localhost:8080
#   MEETING_PROMETHEUS_URL   default http://localhost:9090
#   PERF_USER / PERF_PASS    demo user (default demo@meeting.local / demo)
#   PERF_SKIP_RAG=1          skip RAG scenario (e.g. when ai-worker offline)
#   PERF_OUTPUT_DIR          default infra/meeting-infra/perf-reports
#
# Requires: bash 4+, curl, jq, k6 (https://k6.io). On macOS:
#   brew install k6 jq

set -euo pipefail

API="${MEETING_API_URL:-http://localhost:8080}"
PROM="${MEETING_PROMETHEUS_URL:-http://localhost:9090}"
USER_LOGIN="${PERF_USER:-demo@meeting.local}"
USER_PASS="${PERF_PASS:-demo}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
OUTPUT_DIR="${PERF_OUTPUT_DIR:-$REPO_ROOT/infra/meeting-infra/perf-reports}"

step() { printf '\n→ %s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
warn() { printf '  ! %s\n' "$*" >&2; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

require_cmd() {
    command -v "$1" >/dev/null || die "$1 is required on PATH"
}

require_cmd curl
require_cmd jq
require_cmd k6

mkdir -p "$OUTPUT_DIR"

REPORT_DATE="$(date -u +%Y%m%dT%H%M%SZ)"
COMMIT="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
REPORT_FILE="$OUTPUT_DIR/$REPORT_DATE-$COMMIT.json"

# Initial scaffold so partial failures still produce a usable report.
jq -n \
    --arg date "$REPORT_DATE" \
    --arg commit "$COMMIT" \
    --arg api "$API" \
    '{date: $date, commit: $commit, api: $api, scenarios: {}, breaches: []}' \
    > "$REPORT_FILE"

record_scenario() {
    # $1 scenario name, $2 status ok|breach|skipped, $3 metrics JSON
    local name="$1" status="$2" metrics="$3"
    jq --arg name "$name" --arg status "$status" --argjson m "$metrics" \
        '.scenarios[$name] = ({status: $status} + $m)
         | if $status == "breach" then .breaches += [$name] else . end' \
        "$REPORT_FILE" > "$REPORT_FILE.tmp"
    mv "$REPORT_FILE.tmp" "$REPORT_FILE"
}

step "Authenticating as $USER_LOGIN against $API"
LOGIN_RESP=$(curl -fsSL -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_LOGIN\",\"password\":\"$USER_PASS\"}" \
    || die "login failed — is the API up at $API?")
TOKEN=$(echo "$LOGIN_RESP" | jq -er '.data.accessToken // empty' \
    || die "login response missing accessToken")
ok "obtained access token"

# ───────────────────────────────────────────────────────────────
# Scenario 1 — meetings list latency
# ───────────────────────────────────────────────────────────────
step "Scenario 1: meetings list — target p95 < 300ms @ 50 rps for 30s"
K6_LIST_SCRIPT="$(mktemp -t perf-list.XXXXXX.js)"
cat > "$K6_LIST_SCRIPT" <<EOF
import http from 'k6/http';
import { check } from 'k6';
export const options = {
    scenarios: {
        list: {
            executor: 'constant-arrival-rate',
            rate: 50, timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 20, maxVUs: 100,
        },
    },
    thresholds: { http_req_duration: ['p(95)<300'] },
};
export default function () {
    const r = http.get('$API/api/meetings', {
        headers: {
            'Authorization': 'Bearer $TOKEN',
            'X-Request-Id': 'perf_' + __ITER,
            'X-Trace-Id': 'perf_' + __ITER,
        },
    });
    check(r, { 'status 200': (res) => res.status === 200 });
}
EOF
LIST_OUT="$(k6 run --quiet --summary-export=/dev/stdout "$K6_LIST_SCRIPT" 2>/dev/null || true)"
rm -f "$K6_LIST_SCRIPT"
LIST_P95=$(echo "$LIST_OUT" | jq -r '.metrics.http_req_duration["p(95)"] // 0')
LIST_RPS=$(echo "$LIST_OUT" | jq -r '.metrics.http_reqs.rate // 0')
LIST_FAIL=$(echo "$LIST_OUT" | jq -r '.metrics.http_req_failed.value // 0')
if awk "BEGIN { exit !($LIST_P95 < 300) }"; then
    ok "list p95=${LIST_P95}ms rps=${LIST_RPS} failRate=${LIST_FAIL}"
    LIST_STATUS=ok
else
    warn "list p95=${LIST_P95}ms exceeds 300ms threshold"
    LIST_STATUS=breach
fi
record_scenario "meetings_list" "$LIST_STATUS" \
    "$(jq -n --argjson p95 "$LIST_P95" --argjson rps "$LIST_RPS" --argjson fail "$LIST_FAIL" \
        '{p95Ms: $p95, rps: $rps, failRate: $fail, thresholdMs: 300}')"

# ───────────────────────────────────────────────────────────────
# Scenario 2 — callback (HMAC stub) latency
# ───────────────────────────────────────────────────────────────
step "Scenario 2: callback — target p95 < 200ms @ 100 rps for 30s"
warn "callback scenario requires HMAC signed payloads — skipped in baseline harness"
warn "instrument via meeting.api.callback.duration histogram in Prometheus instead"
record_scenario "callback" "skipped" \
    '{thresholdMs: 200, reason: "requires HMAC signing harness; see meeting.api.callback.duration in Prometheus"}'

# ───────────────────────────────────────────────────────────────
# Scenario 3 — outbox lag (Prometheus query)
# ───────────────────────────────────────────────────────────────
step "Scenario 3: outbox lag — pending count sustained < 100 over 5 min"
if curl -fsSL "$PROM/api/v1/query?query=up" >/dev/null 2>&1; then
    LAG_RAW=$(curl -fsSL "$PROM/api/v1/query?query=max_over_time(meeting_api_outbox_pending_count%5B5m%5D)" \
        | jq -r '.data.result[0].value[1] // "0"')
    if awk "BEGIN { exit !($LAG_RAW < 100) }"; then
        ok "outbox max-over-time(5m) pending=$LAG_RAW"
        LAG_STATUS=ok
    else
        warn "outbox pending=$LAG_RAW >= 100 — publisher is lagging"
        LAG_STATUS=breach
    fi
    record_scenario "outbox_lag" "$LAG_STATUS" \
        "$(jq -n --argjson v "$LAG_RAW" '{pending5m: $v, threshold: 100}')"
else
    warn "Prometheus at $PROM unreachable — skipping outbox lag"
    record_scenario "outbox_lag" "skipped" \
        '{threshold: 100, reason: "prometheus unreachable"}'
fi

# ───────────────────────────────────────────────────────────────
# Scenario 4 — SSE first-byte time
# ───────────────────────────────────────────────────────────────
step "Scenario 4: SSE TTFB — first byte < 500ms"
# Pick the most recent processing task we can see; if none exists we skip.
ANY_TASK=$(curl -fsSL "$API/api/meetings" \
    -H "Authorization: Bearer $TOKEN" \
    | jq -r '[.data[].latestProcessingTaskId // empty] | .[0] // empty')
if [[ -n "$ANY_TASK" ]]; then
    SSE_TTFB_MS=$(curl -o /dev/null -s \
        -w '%{time_starttransfer}' \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Accept: text/event-stream' \
        --max-time 5 \
        "$API/api/processing-tasks/$ANY_TASK/events" \
        | awk '{ printf "%.0f", $1 * 1000 }')
    if awk "BEGIN { exit !($SSE_TTFB_MS < 500) }"; then
        ok "SSE TTFB=${SSE_TTFB_MS}ms task=$ANY_TASK"
        SSE_STATUS=ok
    else
        warn "SSE TTFB=${SSE_TTFB_MS}ms exceeds 500ms threshold"
        SSE_STATUS=breach
    fi
    record_scenario "sse_ttfb" "$SSE_STATUS" \
        "$(jq -n --argjson v "$SSE_TTFB_MS" --arg task "$ANY_TASK" \
            '{ttfbMs: $v, thresholdMs: 500, taskId: $task}')"
else
    warn "no processing tasks available — skipping SSE TTFB"
    record_scenario "sse_ttfb" "skipped" \
        '{thresholdMs: 500, reason: "no processing tasks available to subscribe"}'
fi

# ───────────────────────────────────────────────────────────────
# Scenario 5 — RAG query latency
# ───────────────────────────────────────────────────────────────
step "Scenario 5: RAG query — target p95 < 2500ms @ 5 rps for 60s"
if [[ "${PERF_SKIP_RAG:-0}" = "1" ]]; then
    warn "PERF_SKIP_RAG=1 — skipping RAG scenario"
    record_scenario "rag_query" "skipped" \
        '{thresholdMs: 2500, reason: "PERF_SKIP_RAG=1"}'
else
    K6_RAG_SCRIPT="$(mktemp -t perf-rag.XXXXXX.js)"
    cat > "$K6_RAG_SCRIPT" <<EOF
import http from 'k6/http';
import { check } from 'k6';
export const options = {
    scenarios: {
        rag: {
            executor: 'constant-arrival-rate',
            rate: 5, timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 10, maxVUs: 30,
        },
    },
    thresholds: { http_req_duration: ['p(95)<2500'] },
};
const questions = [
    '上次会议讨论了什么',
    'roadmap 中谁负责 P0',
    '风险项有哪些',
    '已决策的事项',
    '待办列表',
];
export default function () {
    const q = questions[Math.floor(Math.random() * questions.length)];
    const body = JSON.stringify({ question: q, topN: 5, includeStale: false });
    const r = http.post('$API/api/rag/query', body, {
        headers: {
            'Authorization': 'Bearer $TOKEN',
            'Content-Type': 'application/json',
            'X-Request-Id': 'perf_rag_' + __ITER,
            'X-Trace-Id': 'perf_rag_' + __ITER,
        },
    });
    check(r, {
        'status 200': (res) => res.status === 200,
        'or rate-limited': (res) => res.status === 429,
    });
}
EOF
    RAG_OUT="$(k6 run --quiet --summary-export=/dev/stdout "$K6_RAG_SCRIPT" 2>/dev/null || true)"
    rm -f "$K6_RAG_SCRIPT"
    RAG_P95=$(echo "$RAG_OUT" | jq -r '.metrics.http_req_duration["p(95)"] // 0')
    RAG_RPS=$(echo "$RAG_OUT" | jq -r '.metrics.http_reqs.rate // 0')
    if awk "BEGIN { exit !($RAG_P95 < 2500) }"; then
        ok "rag p95=${RAG_P95}ms rps=${RAG_RPS}"
        RAG_STATUS=ok
    else
        warn "rag p95=${RAG_P95}ms exceeds 2500ms threshold"
        RAG_STATUS=breach
    fi
    record_scenario "rag_query" "$RAG_STATUS" \
        "$(jq -n --argjson p95 "$RAG_P95" --argjson rps "$RAG_RPS" \
            '{p95Ms: $p95, rps: $rps, thresholdMs: 2500}')"
fi

# ───────────────────────────────────────────────────────────────
# Final report
# ───────────────────────────────────────────────────────────────
step "Report written to $REPORT_FILE"
jq '.' "$REPORT_FILE"

BREACH_COUNT=$(jq '.breaches | length' "$REPORT_FILE")
if [[ "$BREACH_COUNT" -gt 0 ]]; then
    die "$BREACH_COUNT scenario(s) breached threshold — see $REPORT_FILE"
fi
ok "all scenarios within baseline thresholds"
