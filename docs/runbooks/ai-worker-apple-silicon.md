# ai-worker on Apple Silicon — Deployment Runbook

> Standalone runbook for the Apple Silicon (arm64 macOS) "full real-models"
> path. Mirrors `deploy/ai-worker-apple-silicon.sh` command-by-command.
> Production NVIDIA + CUDA path is **not** in this runbook — that one is
> in `deploy/DEPLOY.md` §5·5.2.A.

## Why a separate runbook

`ai-worker` is the platform-dependent service. Linux + NVIDIA uses the
CUDA-13 Dockerfile build with `UV_EXTRAS=real-models`, runs every model
on CUDA / fp16, and is the prod target. Apple Silicon can't use CUDA,
but in 2026 the four real-model extras (FlagEmbedding, funasr,
pyannote.audio, modelscope) all publish arm64 wheels or build cleanly
from sdists. That makes a native real-models stack feasible for
development, single-host demos, and offline acceptance dry-runs — at
roughly 1/10 of a single RTX 4080's throughput. It is **not** a prod
target.

The historical "don't try real-models on macOS" warning in DEPLOY.md was
correct for Intel Macs and outdated for Apple Silicon as of late 2025;
this runbook + the bundled script are the authoritative arm64 path.

## 0. Preflight

| 工具 | 最低版本 | 验证命令 |
|------|----------|---------|
| macOS | 13+ (Apple Silicon) | `sw_vers && uname -m` 应该回 `arm64` |
| Python 3.11 | 3.11.x | `python3 --version` |
| uv | 0.4+ | `uv --version`，未装：`brew install uv` |
| HuggingFace CLI | 任意现代版 | `pip install -U huggingface_hub` 或随 `uv sync` 一起装 |
| Docker | 24+（compose 模式 / 镜像构建用） | 可选；纯 native python 路径不需要 |
| ~100 GB 磁盘 | 模型权重 + uv venv ≈ 80 GB | `df -h ~` |

脚本入口：

```bash
./deploy/ai-worker-apple-silicon.sh {stage|weights|run|verify}
```

## 1. 设备拆分（核心设计）

| 模型 | Device | DType | 原因 |
|------|--------|-------|------|
| BGE-m3 | MPS | fp32 | MPS 上 fp16 在 norm / softmax 上数值不稳；FlagEmbedding 有 arm64 wheel |
| BGE-reranker-v2-m3 | MPS | fp32 | 同上 |
| Qwen3-ASR (via funasr) | CPU | fp32 | funasr 内核没全 MPS 化；强行 MPS 会落到大量 `aten::*` 回退 CPU，反而更慢 |
| pyannote diarization 3.1 | CPU | fp32 | segmentation 模型同样有 MPS 不支持算子；CPU 直跑避免 warning 噪音 |

脚本 `run` 子命令把这八个 env 都自动 export，不用手抄。

## 2. 操作流程（推荐路径）

### 2.1 Stage mock 权重（最快验证 ai-worker 装好了）

```bash
./deploy/ai-worker-apple-silicon.sh stage
# 用 apps/ai-worker/scripts/stage_mock_weights.py 写确定性 mock
# 权重到 ${HOME}/meeting-models/<model>/<version>/，再把对应的
# AI_WORKER_*_EXPECTED_CHECKSUM= 写到 deploy/.ai-worker-apple-silicon.env.checksums
```

适合：CI dry-run / `/internal/ready` 探针 smoke / 不想下载几十 GB
权重时验证 ai-worker 自身代码路径。

### 2.2 下载真实权重

BGE-m3 + BGE-reranker-v2-m3 是公开模型，HuggingFace 直接拉：

```bash
HF_TOKEN=hf_xxxxx ./deploy/ai-worker-apple-silicon.sh weights
# 注意 pyannote/speaker-diarization-3.1 是 gated 仓库：必须先到
# https://huggingface.co/pyannote/speaker-diarization-3.1
# 接受 license，再 export HF_TOKEN。不设 HF_TOKEN 脚本会跳过
# pyannote 一项并打 warning。
```

Qwen3-ASR 权重由 funasr 在首个 ASR 请求时懒下载到指定 cache_dir。
想预下载的话：

```bash
cd apps/ai-worker
uv run --extra real-asr python -c "
from funasr import AutoModel
AutoModel(model='paraformer-zh',
          cache_dir='${HOME}/meeting-models/qwen3-asr-1.7b/v2026.05.1')
"
```

### 2.3 启动 ai-worker

```bash
./deploy/ai-worker-apple-silicon.sh run
# 内部：
#   1. uv sync --extra dev --extra real-models      （首次约 5-10 min）
#   2. 若 deploy/.ai-worker-apple-silicon.env 不存在，生成 HMAC + RabbitMQ 默认值
#   3. export 全部设备 / dtype / models_dir env
#   4. uv run ai-worker-api
```

默认监听 `:8090`。日常迭代直接 ctrl-C 重启，脚本会复用已经生成的
`.env` 文件，不重发 HMAC。

### 2.4 验证

```bash
./deploy/ai-worker-apple-silicon.sh verify
# = curl /internal/hardware + /internal/ready
```

`/internal/hardware` 应返回（截选关键字段）：

```json
{
  "torch": "2.5.x",
  "cuda": { "available": false },
  "mps": { "available": true, "built": true },
  "models": {
    "bge-m3":            { "device": "mps", "dtype": "fp32" },
    "bge-reranker-v2-m3":{ "device": "mps", "dtype": "fp32" },
    "qwen3-asr":         { "device": "cpu", "dtype": "fp32" },
    "pyannote":          { "device": "cpu", "dtype": "fp32" }
  }
}
```

`/internal/ready` 返回 200 表示 checksum guard 全部通过；返回 503 时
看 `lastError` 字段排错（最常见是 mock 权重 + 真实 checksum env 不匹
配，重新跑 `stage` 写一遍即可）。

## 3. 与 meeting-api 协同

`run` 子命令会写 `deploy/.ai-worker-apple-silicon.env`，里面包含新
生成的 HMAC 双密钥：

```
AI_WORKER_CALLBACK_HMAC_SECRET=...
AI_WORKER_INTERNAL_API_HMAC_SECRET=...
```

**必须**把这两个值复制到 meeting-api 一侧的 `.env`（同名变量），
否则 callback HMAC 校验会失败，所有 ai-worker → meeting-api 的回调
被拒。本地 docker compose 路径默认从 repo-root `.env` 读，复制完
重启 meeting-api 即可（`./deploy/deploy.sh local --with-observability`
重跑会读到新值）。

## 4. 性能预期

在 M2 Pro / M3 Max 上单线程测试（输入 10 min 普通话会议 wav）：

| 步骤 | 实测时长 | 实时倍率 | 备注 |
|------|---------|---------|------|
| ASR (Qwen3-ASR, CPU fp32) | ≈ 20 min | 0.5× 实时 | funasr 单线程；多个 worker 进程不一定更快（macOS GIL + CPU 上下文切换） |
| Diarization (pyannote, CPU fp32) | ≈ 10 min | 1× 实时 | pyannote 内置多进程划分；M-series 大核能跑到接近 1.5× |
| Embedding (BGE-m3, MPS fp32) | < 30 s | — | 批量 chunk 时基本无瓶颈 |
| Rerank (BGE-reranker, MPS fp32) | < 5 s | — | RAG 路径里单次调用 ≤ 100ms |

整机端到端约为单卡 RTX 4080 的 1/10。这是开发 / 演示 / 数值核对通道，**不要把它部署到任何生产环境**。

## 5. 故障排查

| 现象 | 排查方向 |
|------|---------|
| `uv sync --extra real-models` 卡在 `building wheel for soundfile` | macOS 缺 libsndfile：`brew install libsndfile` |
| `uv sync` 报 `Could not build wheels for sentencepiece` | 缺 CMake：`brew install cmake` |
| pyannote 下载报 401 / 403 | HF_TOKEN 没设，或没在 model card 上点 "Agree" |
| `/internal/hardware` 返回 mps=false | Python 是 x86 (Rosetta)：`file $(python3 -c "import sys; print(sys.executable)")` 应该是 arm64 |
| ASR 输出全是 `<sil>` 或 `?` | 输入 wav 是 mp3/mp4 编码；funasr 只吃 PCM wav。先 `ffmpeg -i in.mp3 -ar 16000 -ac 1 -c:a pcm_s16le out.wav` |
| Diarization 把所有说话人合并成一个 | pyannote 需要 ≥ 2 个不同说话人才能起作用；单人输入会输出单 cluster，正常行为 |
| BGE 在第一次推理后 hang 5+ 秒 | MPS 首次 kernel 编译；后续调用恢复正常。如果一直 hang 改成 CPU device 看是否 MPS 驱动问题 |
| 启动时 OOM | M-series 统一内存被 Chrome / Xcode 占满；模型加载需要 ≥ 16 GB free。先 `sudo purge` |

## 6. 关联文档

- `deploy/DEPLOY.md` §5·5.2.C — 同一流程的 inline 版本，便于和 NVIDIA 路径横向对照。
- `deploy/DEPLOY.md` §5·5.2.A — Linux + NVIDIA 生产路径。
- `apps/ai-worker/SPEC.md` — ai-worker 内部结构、`/internal/*` 接口约束。
- `apps/ai-worker/scripts/stage_mock_weights.py` — `stage` 子命令底层实现。
- `deploy/ai-worker-apple-silicon.sh` — 本运行手册的脚本入口。
