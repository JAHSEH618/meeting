# meeting-api (Java) — Deployment Runbook

This runbook is the Java-side operating guide for `meeting-api`. It mirrors
`deploy/meeting-api-java.sh` and points back to `deploy/DEPLOY.md` for shared
K8s, image, and environment-variable details.

`apps/meeting-api` is a Spring Boot 3.3 / Java 17 modular monolith split into
six Maven modules:

| Module | Responsibility |
|--------|----------------|
| `meeting-api-start` | Boot entry point, Spring configuration, profile validation, health indicators |
| `meeting-api-adapter` | REST/SSE/internal callback controllers and protocol adapters |
| `meeting-api-app` | Use-case orchestration, transactions, task scheduling, tenant context |
| `meeting-api-domain` | Aggregates, entities, domain services, repository/gateway ports |
| `meeting-api-infrastructure` | PostgreSQL, RabbitMQ, object storage, KMS, DashScope, LibreOffice gateways |
| `meeting-api-client` | DTOs, commands, results, generated API clients |

## 0. Deployment Decision

| Target | Can start now? | Canonical command | Notes |
|--------|----------------|-------------------|-------|
| Local Java smoke | Yes | `./deploy/meeting-api-java.sh compose` | Starts infra + `meeting-api` + fake `ai-worker`. |
| Phase J local acceptance | Yes, with observability | `./deploy/meeting-api-java.sh compose --with-observability` | Required for Prometheus/Grafana checks. |
| K8s dev / acceptance | Yes, after tool install | `./deploy/deploy.sh k8s-deps dev && ./deploy/meeting-api-java.sh k8s dev` | For kind, build and `kind load` images first. |
| Production | Not directly from dev defaults | ExternalSecrets/Vault + managed dependencies, then `./deploy/meeting-api-java.sh k8s prod` | Do not use dev passwords or in-memory auth. |

For production, treat `k8s-deps prod` as an exception path. The default
recommendation is managed PostgreSQL/RabbitMQ/object storage plus prod overlay
patches and ExternalSecrets.

## 1. Preflight

Run these before touching deploy commands:

```bash
git status --short
java -version
docker version
docker compose version
```

Required versions:

| Tool | Requirement | Why |
|------|-------------|-----|
| JDK | 17, strictly `[17,18)` | Maven Enforcer rejects Java 21/25. |
| Maven | Use bundled `apps/meeting-api/mvnw` | Keeps Maven version pinned. |
| Docker Engine | 24+ | Testcontainers, image build, compose. |
| Node 20 | Only for contract/codegen path | Java generated clients depend on contract output. |
| `kubectl` / `kustomize` / `helm` | K8s only | See `deploy/DEPLOY.md` §2. |

The script auto-detects Java 17 on macOS:

```bash
/usr/libexec/java_home -v 17
```

If you use Colima for Testcontainers:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Rancher Desktop must use dockerd, not pure containerd. OrbStack usually works
without overrides, but setting `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`
is harmless when auto-detection fails.

## 2. Command Matrix

| Flow | Command | Use when |
|------|---------|----------|
| Full Java verification | `./deploy/meeting-api-java.sh test` | CI-equivalent backend gate before deploy. |
| Build jar | `./deploy/meeting-api-java.sh jar` | Fast packaging check. |
| Build and run jar | `./deploy/meeting-api-java.sh jar --run` | Host-native debugging. |
| Build Docker image | `./deploy/meeting-api-java.sh image [tag]` | Local compose / kind / registry push. |
| Cross-build amd64 from Apple Silicon | `./deploy/meeting-api-java.sh image meeting-api:v0.1.0 --cross` | Prod nodes are linux/amd64. |
| Local stack | `./deploy/meeting-api-java.sh compose` | Local app + fake worker. |
| Local acceptance | `./deploy/meeting-api-java.sh compose --with-observability` | Adds Prometheus + Grafana. |
| K8s app deploy | `./deploy/meeting-api-java.sh k8s dev` | After `k8s-deps dev` and image load. |
| Migration recipes | `./deploy/meeting-api-java.sh migrate` | Prints Flyway / restart / psql options. |

## 3. Test Gate

```bash
./deploy/meeting-api-java.sh test
```

This runs:

| Layer | Coverage |
|-------|----------|
| Unit tests | JUnit 5 / Mockito domain and app behavior |
| Architecture tests | COLA module boundary checks |
| Integration tests | Testcontainers PostgreSQL, RabbitMQ, MinIO |
| Spring context | Boot configuration, health indicators, profile validation |

Expected failure classes:

| Symptom | Action |
|---------|--------|
| Maven Enforcer says wrong JDK | Fix `JAVA_HOME` to Java 17. |
| Testcontainers cannot find Docker socket | Apply the Colima/OrbStack env above. |
| Ryuk bind-mount failure on Colima | Set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. |
| Port already used | Stop local compose stack or conflicting containers. |

Do not use a passing `compile` as a deploy gate. The deploy gate is
`./mvnw verify -q`.

## 4. Jar Path

Build only:

```bash
./deploy/meeting-api-java.sh jar
```

Build and run:

```bash
./deploy/meeting-api-java.sh jar --run
```

The jar is:

```text
apps/meeting-api/meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar
```

Host-native jar mode needs external dependencies already running:

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
```

Minimum environment for jar mode:

```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=meeting
export POSTGRES_USER=meeting
export POSTGRES_PASSWORD=meeting_dev

export RABBITMQ_HOST=localhost
export RABBITMQ_PORT=5672
export RABBITMQ_USER=meeting
export RABBITMQ_PASS=meeting_dev

export MINIO_ENDPOINT=http://localhost:9000
export MEETING_STORAGE_ENDPOINT=http://localhost:9000
export MINIO_ROOT_USER=minioadmin
export MINIO_ROOT_PASSWORD=minioadmin

export AI_WORKER_BASE_URL=http://localhost:8090
export AI_WORKER_CALLBACK_HMAC_SECRET=change-me-callback-secret-32bytes
export AI_WORKER_INTERNAL_API_HMAC_SECRET=change-me-internal-secret-32bytes
export KMS_MASTER_KEY_ID=dev-kms-master-key
export MEETING_KMS_MASTER_KEY_BASE64="$(openssl rand -base64 32)"
```

Verification:

```bash
curl -fsSL http://localhost:8080/actuator/health/readiness | jq .
curl -fsSL http://localhost:8080/actuator/health | jq .
```

`/actuator/health/readiness` checks the Java app itself. Aggregate
`/actuator/health` also includes `aiWorker`, `minIo`, `rabbitMqQueue`,
`postgresRls`, `kms`, and `outboxBacklog`.

## 5. Docker Image Path

Build native image:

```bash
./deploy/meeting-api-java.sh image
```

Build with a release tag:

```bash
./deploy/meeting-api-java.sh image meeting-api:v0.1.0
```

Manual equivalent:

```bash
docker build -t meeting-api:dev \
  -f apps/meeting-api/Dockerfile \
  apps/meeting-api/
```

Apple Silicon producing a linux/amd64 image:

```bash
docker buildx create --use 2>/dev/null || true
./deploy/meeting-api-java.sh image meeting-api:v0.1.0 --cross
```

Use cross-build when the target K8s node pool is linux/amd64. Do not push an
arm64-only image to an amd64 cluster.

Image contents:

| Component | Purpose |
|-----------|---------|
| `eclipse-temurin:17-jre-jammy` | Runtime JRE |
| LibreOffice | DOCX/PDF export conversion |
| Spring Boot jar | `meeting-api-start` |
| `/tmp` and `/tmp/soffice` writable volumes | Required by Kubernetes deployment |

## 6. Local Compose Path

Default local stack:

```bash
./deploy/meeting-api-java.sh compose
```

Phase J local acceptance stack:

```bash
./deploy/meeting-api-java.sh compose --with-observability
```

What starts:

| Service | Source | Health gate |
|---------|--------|-------------|
| PostgreSQL + pgvector | compose base | container healthcheck |
| RabbitMQ | compose base | management healthcheck |
| MinIO | compose base | healthcheck |
| Vault | compose base | dev mode |
| meeting-api | `full-stack` profile | `/actuator/health/readiness` |
| ai-worker fake runtime | `workstation` profile | `/internal/health` |
| Prometheus/Grafana | `observability` profile | only when requested |

Why readiness, not aggregate health: aggregate health is expected to be DOWN
until `ai-worker` is available, because `AiWorkerHealthIndicator` is part of
the aggregate group.

Verification:

```bash
./deploy/deploy.sh health
curl -fsSL http://localhost:8080/actuator/health | jq .
curl -fsSL http://localhost:9090/api/v1/rules | jq '.data.groups[].rules | length'
```

## 7. K8s Dev / Acceptance

For kind/minikube:

```bash
./deploy/deploy.sh build
kind create cluster --name meeting-dev
./deploy/deploy.sh k8s-deps dev
kind load docker-image meeting-api:dev meeting-web:dev ai-worker:dev --name meeting-dev
./deploy/meeting-api-java.sh k8s dev
```

`k8s-deps dev` installs namespace dependencies:

| Dependency | Release | Notes |
|------------|---------|-------|
| PostgreSQL | `postgres` | `pgvector/pgvector:pg15`, service DNS `postgres` |
| RabbitMQ | `rabbitmq` | loads `definitions.json`; `auth.securePassword=false` |
| MinIO | `minio` | standalone Deployment by default, buckets auto-created |

`k8s dev` then:

1. Creates `meeting-api-secret` and `ai-worker-secret` for dev.
2. Renders `infra/meeting-infra/k8s/overlays/dev` via `kustomize build --enable-helm`.
3. Applies the bundle.
4. Waits for `deployment/meeting-api`, `deployment/meeting-web`, and `statefulset/ai-worker`.

Verification:

```bash
kubectl get pods -n meeting-dev -o wide
kubectl rollout status deployment/meeting-api -n meeting-dev --timeout=300s
kubectl port-forward -n meeting-dev svc/meeting-api 8080:8080
curl -fsSL http://localhost:8080/actuator/health | jq .
```

## 8. Production K8s

Production deployment has a fixed order:

1. Pass the release gate.
2. Publish immutable images for the target node architecture.
3. Prepare infrastructure, secrets, and config.
4. Confirm database backup and migration policy.
5. Apply the prod overlay.
6. Verify rollout, health, logs, and business smoke checks.

Do not promote a dev or acceptance deployment by changing only the namespace.
The prod overlay enables `SPRING_PROFILES_ACTIVE=prod` and forces
`SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`; `ProdProfileValidator` then
fail-fasts the pod if production-only invariants are missing.

### 8.1 Go / No-Go Gate

| Gate | Go condition | Stop condition |
|------|--------------|----------------|
| Java verification | `./deploy/meeting-api-java.sh test` exits 0 | Any unit, ArchUnit, Testcontainers, or Spring context failure |
| Release image | `meeting-api`, `meeting-web`, and CUDA `ai-worker` images are pushed by digest | Local-only tags, wrong architecture, or missing `ai-worker:cuda-*` image |
| Infrastructure | PostgreSQL, RabbitMQ, object storage, KMS, and monitoring are provisioned | Dev passwords, single-node prod DB/MQ by accident, or no metrics/log access |
| Secrets | `meeting-api-secret` and `ai-worker-secret` are synced in `meeting-prod` | Missing Secret, demo HMAC value, or callback/internal HMAC mismatch |
| Database | Backup exists, restore path is known, Flyway is the migration owner | No backup, raw SQL applied without Flyway history, or baseline-on-migrate enabled |
| AI worker | Linux + NVIDIA + CUDA worker is ready with real model weights | Apple Silicon path, fake runtime, missing checksums, or CPU-only worker |

### 8.2 Release Artifact

Build and test the Java service before creating a release image:

```bash
./deploy/meeting-api-java.sh test
./deploy/meeting-api-java.sh image meeting-api:<release>

# Production linux/amd64 push from Apple Silicon or CI:
docker buildx build --platform linux/amd64 \
  -t registry.example.com/meeting-api:<release> \
  -f apps/meeting-api/Dockerfile \
  --push apps/meeting-api
```

For production, pin images by digest in the release pipeline or prod overlay.
The script's `--cross` helper is useful for local amd64 build sanity checks,
but the production release pipeline should publish the canonical registry tag
and digest.
The prod deployment must include a matched set:

| Image | Requirement |
|-------|-------------|
| `meeting-api` | Java 17 runtime image for the target node architecture |
| `meeting-web` | Web image built from the same release commit |
| `ai-worker` | CUDA image tag, for example `ai-worker:cuda-<release>` |

Do not use an Apple Silicon arm64-only image on linux/amd64 nodes. Do not use
the lean CPU/fake `ai-worker` image for prod, because readiness depends on
real BGE/ASR/diarization dependencies and model weights.

### 8.3 Infrastructure

Recommended production dependency shape:

| Dependency | Production recommendation |
|------------|---------------------------|
| PostgreSQL | Managed RDS / Cloud SQL / self-managed HA PostgreSQL with pgvector |
| RabbitMQ | Managed MQ or HA RabbitMQ, definitions applied by ops |
| Object storage | S3 / OSS / equivalent, not in-cluster MinIO |
| Secrets | Vault / ExternalSecrets / SealedSecrets |
| KMS | Cloud KMS preferred; local KMS requires stable 32-byte base64 master key |
| Monitoring | Managed Prometheus/Grafana or cluster monitoring stack |

Only if explicitly deploying in-cluster production dependencies:

```bash
ALLOW_IN_CLUSTER_PROD_DEPS=1 \
POSTGRES_PASSWORD="<strong-password>" \
RABBITMQ_PASS="<strong-password>" \
MINIO_ROOT_PASSWORD="<strong-password>" \
  ./deploy/deploy.sh k8s-deps prod
```

That exception path is for controlled environments only. It is not the normal
production recommendation.

### 8.4 Secrets and Config

`meeting-api-secret` must exist before rollout:

| Key | Requirement |
|-----|-------------|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | Real DB credentials |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | Real MQ credentials |
| `AI_WORKER_CALLBACK_HMAC_SECRET` | Non-demo, 32+ bytes |
| `AI_WORKER_INTERNAL_API_HMAC_SECRET` | Non-demo, different from callback secret |
| `DASHSCOPE_API_KEY` | Real provider key |
| `KMS_MASTER_KEY_ID` | Not `dev-kms-master-key` |
| `MEETING_KMS_MASTER_KEY_BASE64` | Required if using local KMS gateway |

`ai-worker-secret` must contain the same HMAC pair used by `meeting-api`:

| Key | Requirement |
|-----|-------------|
| `AI_WORKER_CALLBACK_HMAC_SECRET` | Same value as `meeting-api-secret` |
| `AI_WORKER_INTERNAL_API_HMAC_SECRET` | Same value as `meeting-api-secret` |
| `AI_WORKER_ADMIN_JWT_SECRET` | Non-demo admin secret |

Production config must be set through the prod overlay, ExternalSecret, or
cluster platform values:

| Config | Production value |
|--------|------------------|
| `AI_WORKER_BASE_URL` | Cluster DNS / internal URL, never localhost |
| `MEETING_TENANTS_ACTIVE` | Non-empty tenant list |
| `MEETING_STORAGE_ENDPOINT` | Real in-cluster or cloud endpoint |
| `STORAGE_TYPE` / `OSS_*` | Cloud object storage values when not using MinIO |
| Auth mode | Not `in-memory` |
| `SPRING_FLYWAY_BASELINE_ON_MIGRATE` | `false` |

Verify secrets before applying the bundle. `meeting-api-config` is rendered by
Kustomize during apply, so review its prod values from the rendered manifest in
§8.6.

```bash
kubectl get secret meeting-api-secret -n meeting-prod
kubectl get secret ai-worker-secret -n meeting-prod
```

### 8.5 Database Migration

Preferred path: let `meeting-api` run Flyway during startup. Before rollout:

1. Take a database backup.
2. Confirm restore ownership and target RTO/RPO.
3. Review new `V*.sql` files.
4. Keep `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`.
5. Do not run raw `psql` against production unless it is a break-glass action
   and `flyway_schema_history` is repaired afterwards.

If Flyway fails after the pod starts, stop the rollout and either apply a
forward repair migration or restore from the verified backup. Do not disable
`ProdProfileValidator` or turn baseline-on-migrate back on to force startup.

### 8.6 Rollout Order

Render and inspect the prod bundle before applying it:

```bash
kustomize build infra/meeting-infra/k8s/overlays/prod --enable-helm \
  > deploy/.kustomize-prod.yaml
rg 'SPRING_PROFILES_ACTIVE|SPRING_FLYWAY_BASELINE_ON_MIGRATE|AI_WORKER_BASE_URL|MEETING_TENANTS_ACTIVE|MEETING_STORAGE_ENDPOINT' \
  deploy/.kustomize-prod.yaml
kubectl diff -f deploy/.kustomize-prod.yaml
```

Apply through the canonical deploy script:

```bash
./deploy/meeting-api-java.sh k8s prod
kubectl rollout status deployment/meeting-api -n meeting-prod --timeout=300s
kubectl rollout status deployment/meeting-web -n meeting-prod --timeout=300s
kubectl rollout status statefulset/ai-worker -n meeting-prod --timeout=600s
```

If the deployment is intentionally observed by another release controller, use
`./deploy/deploy.sh k8s-prod --no-wait` only when an operator is already
watching rollout status and alerts.

### 8.7 Post-Deploy Verification

Run these after rollout succeeds:

```bash
kubectl logs -n meeting-prod deployment/meeting-api --tail=300 \
  | grep -E 'ProdProfileValidator|Flyway|Started'
kubectl port-forward -n meeting-prod svc/meeting-api 8080:8080
curl -fsSL http://localhost:8080/actuator/health/readiness | jq .
curl -fsSL http://localhost:8080/actuator/health | jq .
```

Acceptance criteria:

| Check | Expected result |
|-------|-----------------|
| Readiness | `UP` for rollout gate |
| Aggregate health | `UP` after ai-worker and dependencies are online |
| Logs | No `ProdProfileValidator`, Flyway, HMAC, KMS, DB, MQ, or storage errors |
| RabbitMQ | Definitions loaded and queues not building unbounded backlog |
| Outbox | `outboxBacklog` health is not DOWN |
| AI worker | `/internal/ready` returns 200 and `/internal/hardware` shows CUDA |

## 9. Database Migration

Preferred path: let `meeting-api` run Flyway during startup.

```bash
kubectl rollout restart deployment/meeting-api -n meeting-dev
kubectl rollout status deployment/meeting-api -n meeting-dev --timeout=300s
```

Flyway CLI path:

```bash
docker run --rm \
  -v "$(pwd)/apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration:/flyway/sql" \
  flyway/flyway:10 \
  -url=jdbc:postgresql://host.docker.internal:5432/meeting \
  -user=meeting \
  -password=meeting_dev \
  -baselineOnMigrate=false \
  migrate
```

SQL debug path:

```bash
ls apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V*.sql \
  | sort \
  | xargs -I{} psql -h localhost -U meeting -d meeting -v ON_ERROR_STOP=1 -f {}
```

Do not use raw `psql` for production migration unless you also handle
`flyway_schema_history`. Raw SQL execution does not mark versions as applied.

## 10. Rollback

| Failure point | Rollback action |
|---------------|-----------------|
| Image fails startup | Repoint overlay image tag to last known-good digest and re-apply |
| Flyway migration fails before apply | Fix SQL and rerun against disposable DB |
| Flyway migration partially applied | Stop rollout; restore DB backup or apply forward repair migration |
| ProdProfileValidator fails | Fix Secret/ConfigMap; do not disable prod profile |
| Health readiness fails | Inspect `/actuator/health/readiness`; check DB/MQ/object storage first |

Useful commands:

```bash
kubectl describe pod -n meeting-prod -l app.kubernetes.io/name=meeting-api
kubectl logs -n meeting-prod deployment/meeting-api --tail=300
kubectl rollout undo deployment/meeting-api -n meeting-prod
```

## 11. Troubleshooting

| Symptom | Likely cause | Action |
|---------|--------------|--------|
| `Detected JDK version` | `JAVA_HOME` points to non-17 | `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` |
| Testcontainers socket error | Docker socket discovery failed | Set Colima/OrbStack env vars |
| `CreateContainerConfigError` | Secret missing | Create `meeting-api-secret` / `ai-worker-secret` before apply |
| `ProdProfileValidator failed` | Prod config still has dev defaults | Compare against §8.3 table |
| `/actuator/health` DOWN but readiness UP | `aiWorker` or dependency aggregate is down | Use readiness for rollout, aggregate for acceptance |
| `outboxBacklog` DOWN | event publisher lag | Check `domain_events_outbox` and logs |
| `MinIoHealthIndicator` DOWN | wrong `MEETING_STORAGE_ENDPOINT` | Use cluster DNS or cloud endpoint, not localhost |
| LibreOffice export fails | missing writable temp or wrong architecture | Check `/tmp/soffice`, image arch, `LIBREOFFICE_BINARY=soffice` |
| Flyway `relation already exists` | SQL was manually applied before Flyway | Use disposable DB, repair, or forward migration |

## 12. Final Checklist

Before starting a deployment window:

- `./deploy/meeting-api-java.sh test` exits 0.
- Release image tag or digest is built for the target node architecture.
- DB backup exists and restore drill is known.
- `meeting-api-secret` is present in target namespace.
- Prod profile values are non-demo.
- Object storage endpoint is reachable from the pod.
- RabbitMQ definitions are loaded.
- `kubectl rollout status deployment/meeting-api` succeeds in staging/acceptance.
- Aggregate `/actuator/health` is UP after ai-worker is online.

Related docs:

- `deploy/DEPLOY.md`
- `docs/runbooks/phase-j-acceptance.md`
- `apps/meeting-api/SPEC.md`
- `deploy/meeting-api-java.sh`
