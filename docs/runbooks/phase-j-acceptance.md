# Phase J — Final Acceptance Runbook

> The nine acceptance checks from `final-check.md` §J. Pass all of them
> and v1 ships. Run in order — later checks depend on prior ones.

All commands assume the repo root unless noted. Capture each step's
output into `infra/meeting-infra/acceptance-reports/<utc-date>/`.

## J1 — Full-stack healthy

Staging cluster up via either compose or K8s dev overlay.

```bash
# Path A — local compose
docker compose --profile full-stack \
    -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
curl -fsSL http://localhost:8080/actuator/health | jq .

# Path B — kind / minikube
# The dev overlay's namespace is `meeting-dev` (see
# infra/meeting-infra/k8s/overlays/dev/kustomization.yaml line 11). An
# overlays/staging tree is reserved but currently empty — use the dev
# overlay until staging-specific patches land.
kind create cluster --name meeting-dev
kubectl apply -k infra/meeting-infra/k8s/overlays/dev
kubectl rollout status deploy/meeting-api -n meeting-dev
kubectl port-forward -n meeting-dev svc/meeting-api 8080:8080 &
curl -fsSL http://localhost:8080/actuator/health | jq .
```

**Pass criteria**

- `/actuator/health` → `{ "status": "UP" }` with all six `HealthIndicator`
  components reporting UP:
  `postgresRls`, `rabbitMqQueue`, `minIo`, `kms`, `aiWorker`,
  `outboxBacklog`.
- Prometheus rules load:
  `curl -fsSL http://localhost:9090/api/v1/rules | jq '.data.groups[].rules | length'`
  returns ≥ 12.
- Grafana shows non-empty panels on each of the 5 dashboards:
  `ai-worker-gpu.json`, `compliance.json`, `meeting-api-overview.json`,
  `rag-quality.json`, `task-pipeline.json`.

## J2 — Prod profile fail-fast

```bash
# Remove a required env var and try to boot prod profile.
docker run --rm -e SPRING_PROFILES_ACTIVE=prod \
    -e AI_WORKER_CALLBACK_HMAC_SECRET="" \
    meeting-api:dev 2>&1 | tee acceptance-reports/$(date -u +%Y%m%d)/j2-fail-fast.log
```

**Pass criteria**: container exits non-zero; the captured log contains:

```
prod profile requires meeting.callback.hmac-secret to be a non-demo value
```

Restoring the env var must let the container start normally.

## J3 — Frontend CSP / bundle budget / XSS

```bash
cd apps/meeting-web
npm ci
npm run build
# Bundle visualization
npx vite-bundle-visualizer --output-format json \
    --output-name bundle-report.json
# Bundle threshold: first-screen gzip < 200 KB
node scripts/check-bundle-budget.mjs bundle-report.json 204800

# CSP — open the production preview and tail the browser console;
# the smoke browser should report 0 violations.
npm run preview &
# Open chrome / playwright and visit a few routes; CSP violations are
# reported to the page console as "Refused to execute …". Capture
# console.log via Playwright if scripting:
npx playwright test --grep "CSP" || true
```

```bash
# XSS — the dedicated SafeMarkdown payload suite.
cd apps/meeting-web
npm test -- --run src/shared/components/__tests__/SafeMarkdown.test.tsx
```

**Pass criteria**

- `bundle-report.json` → first-screen gzip < 200 KB.
- 0 CSP violations on a quick browse through `/login`, `/meetings`,
  `/rag`, `/meetings/.../minutes`.
- 28/28 SafeMarkdown tests green.

## J4 — Model checksum guard

```bash
# 1. Stage staging fixtures (deterministic mock weights). The helper writes
#    every model dir under apps/ai-worker/.cache/staging-models/ by default
#    so we never touch /opt/models on a dev box. --format=dotenv prints the
#    AI_WORKER_*_EXPECTED_CHECKSUM= lines ready to paste into a .env file.
cd apps/ai-worker
uv run python scripts/stage_mock_weights.py --format=dotenv
# To hash an already-staged tree elsewhere, point --root at it:
#   uv run python scripts/stage_mock_weights.py --root /opt/models --force --format=shell
# To compute a single checksum without staging:
#   uv run python -c "from ai_worker.observability.model_checksum import compute_checksum; \
#     print(compute_checksum('/opt/models/bge-m3/v1'))"
# 2. Export the printed envs (or set in K8s ConfigMap), restart ai-worker,
#    then verify the guard sees matching hashes.
hmac-curl GET /internal/models | jq '.data.models[] | {name, checksum, status}'
# 3. Mutate one byte in a weight file → restart ai-worker → /internal/models
#    must report status=ERROR + lastError mentioning the mismatch.
# 4. /internal/ready rolls these up — 503 when any guard fails.
curl -sf -o /dev/null -w "%{http_code}\n" http://localhost:8090/internal/ready
```

**Pass criteria**: corrupted weight ⇒ ready=false; original sha256 ⇒ READY.

## J5 — Playwright stability

```bash
cd apps/meeting-web
for i in 1 2 3 4 5; do
    echo "Run $i"
    npm run e2e --silent | tee logs/e2e-$i.log
done
grep -c "passed (.*)" logs/e2e-*.log | sort
```

**Pass criteria**

- 5 consecutive runs → ≥ 4 wholly green.
- Each run < 10 min wall-clock (capture via `time npm run e2e`).
- Trace artefacts (`apps/meeting-web/test-results/`) preserved for any
  failure.

## J6 — K8s dev overlay on kind / minikube

```bash
kind create cluster --name meeting-j6
kubectl apply -k infra/meeting-infra/k8s/overlays/dev
kubectl get pods -n meeting-dev --watch
```

**Pass criteria**

- Every pod reaches `Ready` within 5 min.
- 0 `CrashLoopBackOff` over a 10-minute soak.
- `kubectl describe pod <name>` shows readinessProbe / livenessProbe
  passing.

## J7 — All unit suites green

```bash
( cd packages/meeting-contracts && npm run check ) && \
( cd apps/meeting-api && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify -q ) && \
( cd apps/meeting-web && npm test && npx tsc --noEmit ) && \
( cd apps/ai-worker && uv run pytest && uv run pyright ai_worker/ )
```

**Pass criteria**: every command exits zero.

### J7 — Colima / non-Docker-Desktop environment notes

`./mvnw verify` boots Testcontainers, and Testcontainers needs to know
where to find the Docker daemon. Recipes by host setup:

| Setup | Required env | Why |
|-------|-------------|-----|
| Docker Desktop (mac/Windows) | _(none)_ | `~/.docker/run/docker.sock` is detected by default. |
| Colima (macOS) | `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`<br>`export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` | Without `DOCKER_HOST` Testcontainers probes `/var/run/docker.sock` which Colima doesn't populate. Without the override Ryuk tries to bind-mount the colima socket *path* into the reaper container and the colima VM can't see it. |
| OrbStack | `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` (usually optional) | OrbStack auto-creates `/var/run/docker.sock`, so most installs work out of the box. |
| Rancher Desktop (containerd backend) | Disable containerd / switch to dockerd, or set the same `DOCKER_HOST` pointing at the dockerd socket. | Testcontainers does not support a pure-containerd backend yet. |

Tool versions on the host must match the repo's `.tool-versions` (java
17 / nodejs 20 / python 3.11). With asdf/mise/rtx the shim picks this up
automatically; otherwise `JAVA_HOME=$(/usr/libexec/java_home -v 17)` is
required to pacify Maven Enforcer `[17,18)`.

The Testcontainers version pinned in `apps/meeting-api/pom.xml`
(currently 1.21.4) supports Docker API auto-negotiation, so Docker 29+
no longer needs `-Dapi.version=1.44`. Earlier 1.20.x baselines did —
keep that workaround in mind if you cherry-pick this runbook back onto
an older branch.

## J8 — Backup recovery drill

Follow `docs/runbooks/backup-recovery.md`:

1. Take a `pg_basebackup` of the staging cluster.
2. Drop the live database.
3. Restore from base + WAL replay.
4. Verify the last meeting row is present.

**Pass criteria**: RTO measured end-to-end < 30 minutes; RPO confirmed
≤ 5 minutes by comparing the latest replayed transaction id to the
captured one.

## J9 — Legal-hold operational drill

Follow `docs/runbooks/legal-hold-procedure.md` and
`docs/runbooks/phase7-acceptance.md`. Quick smoke:

```bash
bash infra/meeting-infra/scripts/legal-hold-lifecycle-smoke.sh
```

**Pass criteria**: the smoke script exits 0; the deeper 6-check
runbook from `phase7-acceptance.md` also passes manually.

---

## After all checks pass

1. File the acceptance result in `todo.md` as:
   `v1 acceptance complete (YYYY-MM-DD)` with a one-line per-check.
2. Tag the release: `git tag -a v1.0.0 -m "v1.0 GA"` (do **not** push
   until the deploy team confirms).
3. Update `final-check.md` block J to all-checked, bump the合计 row to
   53/53, and add a closing 备忘 paragraph linking back to this
   runbook plus the staging environment details.
