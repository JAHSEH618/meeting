# ai-worker on Apple Silicon — Deployment Runbook

This runbook covers the Apple Silicon native `ai-worker` path: full
`real-models` dependencies on arm64 macOS, BGE/rerank on MPS, ASR and
diarization on CPU. It mirrors `deploy/ai-worker-apple-silicon.sh`.

This is a development, acceptance-dry-run, and demo path. It is not a
production serving path. Production model serving remains Linux + NVIDIA +
CUDA image from `deploy/DEPLOY.md` §5.5.2.A.

## 0. Deployment Decision

| Target | Recommended path | Notes |
|--------|------------------|-------|
| Quick local worker smoke | `./deploy/ai-worker-apple-silicon.sh stage && ./deploy/ai-worker-apple-silicon.sh run` | Uses deterministic mock weights. |
| Real-model local demo | `HF_TOKEN=... ./deploy/ai-worker-apple-silicon.sh weights && ./deploy/ai-worker-apple-silicon.sh run` | Needs disk, RAM, and model licenses. |
| Full local Java + native Apple worker | Run meeting-api with `AI_WORKER_BASE_URL=http://host.docker.internal:8090`, then run this worker natively | Compose now allows this override. |
| K8s / production | Do not use this path | Use CUDA image and GPU node pool. |

Apple Silicon is suitable for validating integration logic, HMAC callbacks,
model wiring, readiness, checksum guard, and UI workstation flows. It is not
suitable for production throughput or SLO testing.

## 0.1 Production Boundary and Handoff

This runbook is allowed to feed production readiness decisions, but it is not
allowed to become the production runtime. Production ai-worker serving must use
the Linux + NVIDIA + CUDA path described in `deploy/DEPLOY.md` §5.5.2.A.

Use the Apple Silicon path for:

| Use | Allowed? | Notes |
|-----|----------|-------|
| Local real-model integration | Yes | Validates request/response contracts and callback HMACs. |
| Demo with real BGE/ASR/diarization wiring | Yes | Do not report these numbers as production SLOs. |
| Model checksum rehearsal | Yes | Checksums can be copied into the production model registry after review. |
| Production Kubernetes serving | No | Use CUDA image, GPU node pool, and production model volume. |
| Production performance benchmark | No | MPS/CPU behavior does not represent CUDA throughput. |

The handoff from this runbook to production is a release note, not a copy of
the Mac environment. Capture only these outputs:

| Handoff item | Production use |
|--------------|----------------|
| Model names and versions | Populate the production model registry / PVC layout. |
| SHA-256 checksums | Set `AI_WORKER_*_EXPECTED_CHECKSUM=sha256:...` in prod. |
| Contract test results | Evidence that meeting-api and ai-worker agree on payloads and HMACs. |
| Audio smoke samples | Re-run on the CUDA worker before production rollout. |
| Known model licenses | Confirm pyannote/HuggingFace terms are accepted for the deployment account. |

Do not copy `deploy/.ai-worker-apple-silicon.env` into production. It contains
local URLs, local RabbitMQ credentials, and locally generated HMAC/JWT values.
Production secrets must come from Vault, ExternalSecrets, SealedSecrets, or the
target platform's secret manager.

Production ai-worker must satisfy all of these:

| Requirement | Production value |
|-------------|------------------|
| Image | `ai-worker:cuda-<release>` or equivalent digest |
| Node pool | NVIDIA GPU nodes labeled for the ai-worker StatefulSet |
| Runtime flags | `AI_WORKER_USE_FAKE_RUNTIME=false`, `AI_WORKER_USE_FAKE_ASR_RUNTIME=false`, `AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false` |
| Offline mode | `HF_HUB_OFFLINE=1`, `TRANSFORMERS_OFFLINE=1` after weights are staged |
| Model volume | `/opt/models/<model>/<version>/` with expected checksums |
| Secrets | `ai-worker-secret` synced before `kubectl apply` |
| Hardware check | `/internal/hardware` shows CUDA available and MPS not used |
| Readiness | `/internal/ready` returns 200 after checksum validation |

Production blockers:

| Blocker | Action |
|---------|--------|
| Worker runs on MPS or CPU | Stop; deploy CUDA image to GPU node pool. |
| `AI_WORKER_USE_FAKE_*` is true | Stop; fake runtime is not production traffic. |
| Model checksum is absent or mismatched | Stop; restage model weights and update expected checksums. |
| pyannote license is not accepted by the deployment account | Stop; complete license approval before image/model promotion. |
| Mac `.env` file is referenced by prod manifests | Stop; replace with production Secret manager values. |
| `/internal/hardware` reports `cuda.available=false` | Stop; fix node scheduling, driver, image, or torch/CUDA build. |

## 1. Preflight

Run:

```bash
sw_vers
uname -m
python3 --version
uv --version
df -h "$HOME"
```

Requirements:

| Item | Requirement | Why |
|------|-------------|-----|
| Hardware | Apple Silicon, `uname -m` = `arm64` | x86_64 macOS is not supported for real-models. |
| macOS | 13+ | Modern MPS and Python wheel compatibility. |
| Python | 3.11.x | `ai-worker` requires Python 3.11. |
| uv | 0.4+ | Dependency sync and isolated venv. |
| Disk | 100 GB free recommended | Model weights + uv cache can approach 80 GB. |
| Memory | 32 GB recommended, 16 GB minimum for smoke | ASR + diarization + browser/Java consume unified memory. |
| Network | HuggingFace access | Required for BGE and pyannote downloads. |

Install common native dependencies:

```bash
brew install uv python@3.11 ffmpeg cmake pkg-config libsndfile
```

Rosetta check:

```bash
file "$(python3 -c 'import sys; print(sys.executable)')"
```

The output must mention `arm64`. If it says `x86_64`, stop and switch to an
arm64 Python/uv environment.

## 2. Command Matrix

| Command | Purpose | Safe offline? |
|---------|---------|---------------|
| `./deploy/ai-worker-apple-silicon.sh stage` | Writes deterministic mock weights and checksum env | Yes |
| `HF_TOKEN=... ./deploy/ai-worker-apple-silicon.sh weights` | Downloads real BGE and pyannote weights | No |
| `./deploy/ai-worker-apple-silicon.sh env` | Creates/reuses local connection + HMAC env file | Yes |
| `./deploy/ai-worker-apple-silicon.sh run` | Installs extras and starts `ai-worker-api` on `:8090` | Depends on weights/cache |
| `./deploy/ai-worker-apple-silicon.sh verify` | Calls `/internal/hardware` and `/internal/ready` | Requires worker running |

Default model root:

```bash
export AI_WORKER_MODELS_ROOT="$HOME/meeting-models"
```

Override when disk space is elsewhere:

```bash
AI_WORKER_MODELS_ROOT=/Volumes/models/meeting-models \
  ./deploy/ai-worker-apple-silicon.sh stage
```

## 3. Architecture

Device routing is explicit:

| Model / capability | Device | DType | Reason |
|--------------------|--------|-------|--------|
| BGE-m3 embedding | MPS | fp32 | FlagEmbedding works on arm64; fp16 on MPS is numerically risky. |
| BGE-reranker-v2-m3 | MPS | fp32 | Same as BGE-m3. |
| Qwen3-ASR / funasr | CPU | fp32 | funasr operators are not fully MPS-clean. |
| pyannote diarization | CPU | fp32 | MPS fallback warnings are noisy and often slower. |

The script exports:

```bash
AI_WORKER_BGE_M3_DEVICE=mps
AI_WORKER_BGE_RERANKER_DEVICE=mps
AI_WORKER_BGE_M3_DTYPE=fp32
AI_WORKER_BGE_RERANKER_DTYPE=fp32
AI_WORKER_ASR_DEVICE=cpu
AI_WORKER_DIARIZATION_DEVICE=cpu
```

Do not force ASR or diarization to MPS unless you are debugging a specific
upstream kernel change.

## 4. Mock-Weight Smoke

Use this when you want the worker to boot and pass readiness without pulling
large model repositories:

```bash
./deploy/ai-worker-apple-silicon.sh stage
./deploy/ai-worker-apple-silicon.sh run
```

`stage` writes mock model directories:

```text
~/meeting-models/
  bge-m3/v1/
  bge-reranker-v2-m3/v1/
  qwen3-asr-1.7b/v2026.05.1/
  pyannote/v3.1/
```

It also writes:

```text
deploy/.ai-worker-apple-silicon.env.checksums
```

If you want checksum guard to validate those exact mock weights during `run`,
source the checksum file before starting:

```bash
set -a
. deploy/.ai-worker-apple-silicon.env.checksums
set +a
./deploy/ai-worker-apple-silicon.sh run
```

## 5. Real-Weight Path

Download public BGE weights and gated pyannote weights:

```bash
HF_TOKEN=hf_xxx ./deploy/ai-worker-apple-silicon.sh weights
```

Before downloading pyannote, accept the model terms on HuggingFace:

```text
https://huggingface.co/pyannote/speaker-diarization-3.1
```

Qwen3-ASR is fetched lazily by funasr. To warm it ahead of a demo:

```bash
cd apps/ai-worker
AI_WORKER_MODELS_ROOT=${AI_WORKER_MODELS_ROOT:-$HOME/meeting-models}
uv run --extra real-asr python - <<'PY'
from funasr import AutoModel
import os
root = os.environ["AI_WORKER_MODELS_ROOT"]
AutoModel(
    model="paraformer-zh",
    cache_dir=f"{root}/qwen3-asr-1.7b/v2026.05.1",
)
PY
```

After real weights are in place:

```bash
./deploy/ai-worker-apple-silicon.sh run
```

For offline demos:

```bash
AI_WORKER_OFFLINE=1 ./deploy/ai-worker-apple-silicon.sh run
```

Only set offline mode after all required files are present locally.

## 6. Environment File

The first `run` creates:

```text
deploy/.ai-worker-apple-silicon.env
```

It contains local connection and HMAC values:

```bash
AI_WORKER_RABBITMQ_HOST=localhost
AI_WORKER_RABBITMQ_PORT=5672
AI_WORKER_RABBITMQ_USERNAME=meeting
AI_WORKER_RABBITMQ_PASSWORD=meeting_dev
AI_WORKER_MEETING_API_BASE_URL=http://localhost:8080
AI_WORKER_JAVA_API_BASE_URL=http://localhost:8080
AI_WORKER_CALLBACK_HMAC_SECRET=...
AI_WORKER_INTERNAL_API_HMAC_SECRET=...
AI_WORKER_ADMIN_JWT_SECRET=...
```

The script does not overwrite this file. Rotate it manually when you want new
secrets:

```bash
mv deploy/.ai-worker-apple-silicon.env deploy/.ai-worker-apple-silicon.env.old
./deploy/ai-worker-apple-silicon.sh run
```

## 7. Integrating With meeting-api

There are two supported local integration shapes.

### Option A: Compose worker, fake runtime

Use this for everyday local development:

```bash
./deploy/deploy.sh local --with-observability
```

This starts the compose `ai-worker:dev` container in fake runtime. It does not
use the Apple Silicon native real-model worker.

### Option B: Native Apple worker, compose meeting-api

Use this for real-model integration on a Mac:

```bash
# 1. Create/reuse the shared HMAC env before starting meeting-api.
./deploy/ai-worker-apple-silicon.sh env
set -a
. deploy/.ai-worker-apple-silicon.env
set +a

# 2. Start dependencies only.
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d

# 3. Start meeting-api and point it to the host-native worker. The HMAC
#    variables sourced above are also passed into compose.
AI_WORKER_BASE_URL=http://host.docker.internal:8090 \
  docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile full-stack up -d meeting-api

# 4. Start native Apple worker in another terminal.
./deploy/ai-worker-apple-silicon.sh run
```

Why `host.docker.internal`: `meeting-api` runs in Docker, while the native
worker listens on the macOS host at `localhost:8090`. From inside the
container, that host is `host.docker.internal`.

Copy HMAC values both ways:

| Direction | Variable |
|-----------|----------|
| ai-worker -> meeting-api callback | `AI_WORKER_CALLBACK_HMAC_SECRET` |
| meeting-api -> ai-worker internal API | `AI_WORKER_INTERNAL_API_HMAC_SECRET` |

If these differ between processes, callbacks and rerank/internal calls fail
with HMAC errors.

## 8. Verification

With ai-worker running:

```bash
./deploy/ai-worker-apple-silicon.sh verify
```

Manual:

```bash
curl -fsSL http://localhost:8090/internal/hardware | jq .
curl -fsSL http://localhost:8090/internal/ready | jq .
```

Expected hardware shape:

```json
{
  "cuda": { "available": false },
  "mps": { "available": true, "built": true },
  "models": {
    "bge-m3": { "device": "mps", "dtype": "fp32" },
    "bge-reranker-v2-m3": { "device": "mps", "dtype": "fp32" },
    "qwen3-asr": { "device": "cpu", "dtype": "fp32" },
    "pyannote": { "device": "cpu", "dtype": "fp32" }
  }
}
```

Readiness interpretation:

| Status | Meaning |
|--------|---------|
| 200 | Model dirs exist and checksum guard passed. |
| 503 with checksum mismatch | Expected checksum env does not match files. Re-run `stage` or update checksum env. |
| 503 with missing path | Weight directory is absent or wrong `AI_WORKER_MODELS_ROOT`. |
| 503 with import error | Real-model extra did not install; rerun `uv sync --extra dev --extra real-models`. |

## 9. Performance Expectations

Approximate local demo numbers:

| Step | Expected speed | Notes |
|------|----------------|-------|
| Embedding | fast enough for UI demos | MPS fp32, batch size matters |
| Rerank | sub-second to a few seconds | Candidate count dominates |
| ASR | slower than realtime on many inputs | CPU fp32, do not compare with CUDA |
| Diarization | around realtime on M2/M3 class machines | CPU-bound |

Keep Chrome, Xcode, Docker Desktop, and large IDE indexing under control. The
unified-memory pressure is the common failure mode.

## 10. Audio Input Rules

For ASR/diarization sanity tests, normalize audio first:

```bash
ffmpeg -i input.mp4 \
  -ar 16000 \
  -ac 1 \
  -c:a pcm_s16le \
  sample-16k-mono.wav
```

Avoid feeding compressed MP3/MP4 directly to low-level smoke scripts. The API
upload path may normalize, but direct local debugging is simpler with PCM WAV.

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Script says not Apple Silicon | Running on Intel/Rosetta/Linux | Use fake runtime or CUDA Linux path. |
| `/internal/hardware` says `mps=false` | x86 Python or old macOS/PyTorch | Switch to arm64 Python, update macOS/uv env. |
| `uv sync` fails on `soundfile` | Missing native library | `brew install libsndfile pkg-config`. |
| `sentencepiece` build fails | Missing build tools | `brew install cmake`. |
| pyannote returns 401/403 | License not accepted or token absent | Accept model terms and set `HF_TOKEN`. |
| `/internal/ready` checksum mismatch | Mock checksum env with real weights, or stale checksum file | Regenerate checksum env for the files in use. |
| HMAC callback rejected by Java | Secrets differ between worker and meeting-api | Copy `deploy/.ai-worker-apple-silicon.env` HMAC values into meeting-api env. |
| meeting-api cannot reach native worker | Docker container uses `http://ai-worker:8090` | Start compose with `AI_WORKER_BASE_URL=http://host.docker.internal:8090`. |
| ASR emits empty/silence output | Bad audio format or too quiet input | Convert to 16k mono PCM WAV and retry. |
| First inference pauses for seconds | MPS kernel compilation | Expected on first call; repeat once before measuring. |
| OOM / process killed | unified memory pressure | Stop Docker/IDE/browser workloads; reduce batch sizes; use mock mode. |

## 12. Cleanup

Stop worker:

```bash
# Ctrl-C in the run terminal
```

Remove generated env:

```bash
rm -f deploy/.ai-worker-apple-silicon.env
rm -f deploy/.ai-worker-apple-silicon.env.checksums
```

Remove mock/real weights:

```bash
rm -rf "${AI_WORKER_MODELS_ROOT:-$HOME/meeting-models}"
```

Clear uv cache only if disk pressure is severe:

```bash
uv cache clean
```

## 13. Final Checklist

Before using Apple Silicon real-models in a demo or production-readiness
handoff:

- `uname -m` returns `arm64`.
- `uv sync --extra dev --extra real-models` completes.
- Model directories exist under `AI_WORKER_MODELS_ROOT`.
- HMAC values match meeting-api.
- `/internal/hardware` shows BGE on `mps` and ASR/diarization on `cpu`.
- `/internal/ready` returns 200.
- A short PCM WAV smoke input has been tested.
- The audience understands this is not the production performance profile.
- Only model versions, checksums, contract evidence, and license notes are
  handed off to production.
- Production deployment still uses the CUDA image, GPU node pool,
  production Secret manager values, and `/opt/models` model volume.

Related docs:

- `deploy/DEPLOY.md` §5.5.2
- `docs/runbooks/meeting-api-java.md`
- `docs/runbooks/phase-j-acceptance.md`
- `apps/ai-worker/SPEC.md`
- `deploy/ai-worker-apple-silicon.sh`
