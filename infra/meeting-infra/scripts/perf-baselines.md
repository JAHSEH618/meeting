# Performance Baseline

> Reference numbers for `infra/meeting-infra/scripts/perf-baseline.sh`. The
> script asserts these thresholds on a fresh full-stack run and exits
> non-zero on breach, so it can gate releases from CI.

## Run

```bash
# 1. start full-stack (pg + rabbit + minio + meeting-api + ai-worker)
docker compose --profile full-stack \
    -f infra/meeting-infra/docker/compose/docker-compose.yml up -d

# 2. run the harness
bash infra/meeting-infra/scripts/perf-baseline.sh
```

Report lands at `infra/meeting-infra/perf-reports/<utc-date>-<commit>.json`.

## Scenarios and thresholds

| # | Scenario | Endpoint / metric | Threshold | Why |
|---|---|---|---|---|
| 1 | `meetings_list` | `GET /api/meetings` | p95 < **300 ms** @ 50 rps | The default landing page must feel instant; 300 ms is the upper bound where users start perceiving lag (Nielsen). |
| 2 | `callback` | `meeting.api.callback.duration` histogram | p95 < **200 ms** | Workers send heartbeat callbacks every 15–30 s; a slow callback chain holds the lease open and inflates outbox lag. |
| 3 | `outbox_lag` | `meeting_api_outbox_pending_count` 5-min max | < **100 rows** | Anything higher means the publisher can't keep up with writers; downstream consumers (RabbitMQ → ai-worker) starve. |
| 4 | `sse_ttfb` | `GET /api/processing-tasks/{id}/events` first byte | < **500 ms** | UX threshold for "did my click work" on the task progress page. |
| 5 | `rag_query` | `POST /api/rag/query` | p95 < **2 500 ms** @ 5 rps | Includes embed + retrieve + rerank + LLM round-trip. 2.5 s is the hard ceiling where users start retyping the question; targets <1.5 s once the bge-reranker is hot. |

### Why not assert against callback throughput?

The callback path is HMAC-signed and tenant-scoped; faithfully reproducing
auth in a load script bloats the harness and risks signing-secret leakage.
Instead, the histogram `meeting.api.callback.duration` is scraped continuously
by Prometheus — the `perf-baseline-callback-p95` alert in
`infra/meeting-infra/observability/prometheus/rules.yaml` fires at the same
200 ms threshold, so coverage is preserved without duplicating signing logic
in the script.

## Reading the report

Each scenario produces one of three statuses:

- `ok` — measured value below threshold.
- `breach` — measured value at/above threshold (the only condition that
  exits the script non-zero).
- `skipped` — preconditions absent (e.g. no processing task to subscribe
  to, ai-worker offline, Prometheus unreachable). Skipped scenarios never
  fail the script but are explicitly recorded in JSON so a CI dashboard
  can flag chronic skips.

Top-level `.breaches` array lists the failing scenario names for fast
triage.

## Tuning

If a baseline becomes consistently green with margin, tighten the
threshold in this doc + the script — keeping the perf budget visible
prevents silent regression. If you must temporarily loosen, open an
issue with the new ceiling and the date by which it must return.

## Future work

- Replace the inline k6 scripts with `k6/scripts/*.js` once the suite
  grows past 3 scenarios; current inline form is faster to read but
  doesn't survive a refactor.
- Wire the harness into `.github/workflows/ci.yml` under a `perf-smoke`
  job that runs against a kind cluster nightly.
- Add a sixth scenario for **export render p95** once the rate is
  representative enough not to skew the warm cache (currently a single
  PDF render dominates the sample).
