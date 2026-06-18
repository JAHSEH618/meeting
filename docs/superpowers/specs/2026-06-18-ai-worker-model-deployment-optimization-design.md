# AI Worker 模型部署优化方案

**日期**: 2026-06-18
**状态**: 新版总方案
**目标范围**: Python `apps/ai-worker` 真实模型部署、Mac 本地部署、模型权重供应链、镜像矩阵、GPU/MPS/CPU 加速、Kubernetes 发布、warmup、checksum、灰度和回滚。
**取代文档**: 已删除旧版 `2026-06-16-ai-worker-stability-perf-optimization-design.md` 和旧实施计划 `2026-06-16-ai-worker-stage1-stability.md`。旧版重点是 RabbitMQ 稳定性和通用性能，本方案改为以 Python 端模型部署为主线。

## 1. 背景和当前判断

当前 ai-worker 已经不是从零开始的模型服务。仓库里已经有一批可复用的基础能力：

- `apps/ai-worker/Dockerfile` 已支持多阶段构建、`BASE` 切换、`UV_EXTRAS` 能力子集安装、离线 HuggingFace 环境变量和非 root 运行。
- `apps/ai-worker/pyproject.toml` 已按能力拆出 `real-bge`、`real-asr`、`real-diarization`、`real-speaker`、`real-models` extras。
- `apps/ai-worker/ai_worker/model_runtime/registry.py` 已有 per-model device resolution、fake/real runtime 切换和 dtype 策略。
- `apps/ai-worker/ai_worker/model_runtime/concurrency.py` 已有 per-device semaphore，单 GPU 默认可以串行化模型加载和推理。
- `apps/ai-worker/ai_worker/interfaces/api/main.py` 已提供 `/internal/models`、`/internal/models/warmup`、`/internal/hardware`、`/internal/ready`、`/metrics`。
- `infra/meeting-infra/k8s/base/ai-worker/statefulset.yaml` 已有 GPU nodeSelector/toleration、模型 PVC、readiness/liveness probe、Prometheus scrape annotations。
- `docs/model-registry.md` 已定义模型准入、checksum、内网制品路径和容器挂载点。
- `docs/runbooks/ai-worker-apple-silicon.md` 已有 Apple Silicon 原生运行路径；它不是最终生产 serving 路径，但必须作为本地真实模型调试、两机联调和演示验收的一等部署场景纳入总方案。

因此，新方案不应该再创建并行 Dockerfile、并行健康检查入口或新的任务框架。真正要补齐的是一条可重复、可观测、可回滚的模型部署链路：

1. 模型权重如何准入、打包、校验、分发。
2. 镜像如何按能力构建，避免一个 `real-models` 大镜像承载所有场景。
3. K8s 如何按能力拆 Pod，让 ASR、diarization、embedding/rerank 的依赖和显存互相隔离。
4. 冷启动、warmup、readiness、checksum 如何配合，避免接流量过早或重启风暴。
5. 模型版本如何灰度、回滚，并和发布记录绑定。
6. Mac 本地如何在 arm64/MPS/CPU 混合环境下稳定运行真实模型，并尽量缩短开发反馈时间。
7. ASR、diarization、embedding/rerank 如何通过设备路由、batch、chunk、缓存和队列策略加速。

## 2. 目标

### 2.1 主要目标

1. **可重复部署**: 同一组镜像 tag、模型版本、checksum、K8s overlay 和 Secret 可以在 staging/prod 重建相同运行状态。
2. **运行时离线**: 生产运行时不依赖 HuggingFace、ModelScope 或公网下载；缺权重必须 fail loud。
3. **镜像轻量化**: 按能力构建镜像，避免 BGE worker 安装 pyannote，ASR worker 安装 FlagEmbedding。
4. **模型权重可追溯**: 每个模型版本有来源、license、checksum、审批状态、制品路径和部署记录。
5. **GPU 资源隔离**: ASR、diarization、embedding/rerank 默认分 profile 部署，降低显存峰值叠加和依赖冲突。
6. **冷启动可控**: startupProbe、init 校验、显式 warmup 和 readiness 语义配合，避免真实模型加载慢时被 liveness 误杀。
7. **灰度可回滚**: 模型升级不覆盖旧目录，灰度失败时只回滚 ConfigMap/Secret/Pod template。
8. **验收自动化**: 每个镜像和模型 bundle 都有 smoke test，部署前后都有固定检查命令。
9. **Mac 本地可用**: Apple Silicon 可以原生运行 ai-worker，并通过 MPS/CPU 混合路由完成真实模型调试和两机联调。
10. **推理可加速**: BGE/rerank 使用 batch 和 MPS/CUDA，ASR 使用音频切片和按需 alignment，diarization 使用人数约束和缓存，避免单纯堆机器。

### 2.2 成功标准

- 所有生产 `real-*` 镜像使用 frozen lock 构建，不能 fallback resolve。
- 生产 Pod 在模型目录缺失、checksum mismatch 或 required package 缺失时 `/internal/ready` 返回 503。
- `/internal/health` 只表示进程存活，不因模型 checksum mismatch 触发重启风暴。
- `POST /internal/models/warmup?capabilities=...` 可以按能力预热模型，并把失败反映到 `/internal/models`。
- BGE、ASR、diarization 三类能力可以独立构建、独立部署、独立扩缩容。
- 模型版本升级时旧权重目录保留，回滚不需要重新下载权重。
- Apple Silicon Mac 在 arm64 原生 Python 下可以完成真实 BGE/rerank、ASR、diarization 的 smoke test，且 `/internal/hardware` 能清楚暴露 MPS/CPU/package 状态。
- 加速策略有可观测指标支撑，至少记录 RTF、batch size、chunk 时长、显存/内存峰值和失败率。

## 3. 非目标

- 不重写 RabbitMQ consumer，不引入 Celery、Temporal 或新的任务框架。
- 不改变 Java 管业务事实和权限、Python 管模型计算的边界。
- 不把模型权重 bake 进默认运行镜像。镜像包含运行依赖，权重通过只读 PVC、节点 cache 或制品同步提供。
- 不新增 `apps/ai-worker/Dockerfile.optimized`。继续演进现有 Dockerfile。
- 不新增独立 metrics 端口。继续使用 `8090/metrics`。
- 不在 Stage 1 强行落地尚未完成的 ForcedAligner production runtime。CAM++ 已纳入 Stage 1 的 registry / worker 默认构造 / warmup / checksum 路线，后续 speaker 阶段聚焦真实权重质量、片段选择和 CPU/MPS/CUDA 性能压测。
- 不把 Mac 本地部署定义为生产 serving 路径。生产默认仍是 Linux + NVIDIA + CUDA + K8s GPU 节点池。
- 不在 Mac 上强行把所有模型塞进 MPS。MPS 只用于已验证稳定的模型；不稳定模型优先 CPU。

## 4. 设计原则

1. **镜像和权重分离**: 镜像表达代码和 Python 依赖，权重表达模型版本。两者在发布记录中绑定，但物理制品分开。
2. **版本目录不可变**: `/opt/models/<model>/<version>` 一旦进入生产，不允许原地覆盖。新模型必须新目录。
3. **显式配置优先**: ConfigMap 指向具体版本目录，Secret 提供 expected checksum。不要依赖浮动 symlink 作为生产事实。
4. **启动校验前置**: initContainer 或 startup script 先校验模型目录和 checksum，再启动主进程。
5. **readiness fail loud**: 模型不可用时拒绝 Ready，但 liveness 不因为模型错误重启。
6. **能力隔离优先**: 默认拆成 BGE、ASR、diarization、speaker profile；`all-gpu` 只做验收或小规模 fallback。
7. **灰度不改数据**: 灰度只改 Pod template、ConfigMap、Secret 和队列路由，不在灰度过程中改业务数据结构。
8. **低基数指标，高基数日志**: Prometheus 只放 model/profile/status 等低基数字段，具体路径、checksum 放日志和发布记录。
9. **Mac 原生优先**: Mac 本地真实模型部署使用 arm64 Python + uv + Metal/MPS，不用 Docker 模拟生产 CUDA。
10. **先稳后快**: 加速优化先以稳定设备路由、可重复 benchmark 和可回滚配置为前提，再引入更激进的量化、compile 或分段并行。

## 5. 目标架构

### 5.1 制品关系

一次模型部署由四类制品组成：

| 制品 | 示例 | 说明 |
|---|---|---|
| 代码镜像 | `ai-worker:<git_sha>-bge-cuda13-py311` | 包含 Python 代码和对应 heavy deps |
| 模型 bundle | `models/bge-m3/v1` | 权重、license、manifest、checksum |
| K8s overlay | `ai-worker-bge` profile | 绑定镜像、队列、资源、模型路径 |
| 发布记录 | release manifest | 绑定 git sha、image digest、model checksum、operator、时间 |

发布记录建议保存为结构化文件，例如：

```yaml
releaseId: ai-worker-models-2026-06-18-001
gitSha: "<commit>"
profiles:
  bge:
    image: "ai-worker:<git_sha>-bge-cuda13-py311"
    imageDigest: "sha256:<image_digest>"
    models:
      bge-m3:
        path: "/opt/models/bge-m3/v1"
        checksum: "sha256:<checksum>"
      bge-reranker-v2-m3:
        path: "/opt/models/bge-reranker-v2-m3/v1"
        checksum: "sha256:<checksum>"
  asr:
    image: "ai-worker:<git_sha>-asr-cuda13-py311"
    models:
      qwen3-asr-1.7b:
        path: "/opt/models/qwen3-asr-1.7b/v2026.05.1"
        checksum: "sha256:<checksum>"
```

### 5.2 运行 profile

| Profile | 镜像 extras | 队列 | 模型 | 资源策略 |
|---|---|---|---|---|
| `api-cpu` | 空 | 无或 admin only | fake runtime | CPU，小内存，适合 workstation BFF/dev |
| `bge-gpu` | `real-bge` | `embed-queue`, `rerank-queue` | bge-m3, bge-reranker | 1 GPU，可多副本 |
| `asr-gpu` | `real-asr` | `gpu-asr-queue` | Qwen3-ASR | 1 GPU，默认并发 1 |
| `diar-gpu` | `real-diarization` | `gpu-diar-queue` | pyannote | 1 GPU，默认并发 1 |
| `speaker-gpu` | `real-speaker` 或 `real-models` | `gpu-speaker-queue` | 3D-Speaker CAM++ | 1 GPU/CPU，默认并发 1 |
| `all-gpu` | `real-models` | 全部任务队列 | 全部模型 | 仅用于验收、单机部署或 fallback |

长期生产默认使用 `bge-gpu` + `asr-gpu` + `diar-gpu` + `speaker-gpu`。`all-gpu` 保留，但不是默认目标。

### 5.3 模型目录布局

容器内固定布局：

```text
/opt/models/
  bge-m3/v1/
    MODEL_MANIFEST.json
    LICENSE
    model.safetensors
  bge-reranker-v2-m3/v1/
  qwen3-asr-1.7b/v2026.05.1/
  pyannote/v3.1/
  cam_plus/v1/
```

要求：

- ConfigMap 指向版本目录，例如 `AI_WORKER_BGE_M3_MODELS_DIR=/opt/models/bge-m3/v1`。
- Secret 或 ExternalSecret 提供 `AI_WORKER_BGE_M3_EXPECTED_CHECKSUM=sha256:<hex>`。
- `MODEL_MANIFEST.json` 至少包含 `name`、`version`、`source`、`license`、`createdAt`、`checksum`、`files`。
- 权重目录只读挂载到主容器。

### 5.4 部署场景矩阵

| 场景 | 运行方式 | 目标 | 模型来源 | 加速设备 |
|---|---|---|---|---|
| Mac 单机本地 | `uv run ai-worker-api` / control script | 开发、真实模型 smoke、UI/BFF 调试 | `$HOME/meeting-models` 或外置盘 | MPS + CPU |
| Mac + 远端 Java/RabbitMQ | Mac 原生 ai-worker，CentOS/ECS 跑 Java 依赖 | 两机联调、演示验收 | Mac 本地权重 + OSS/TOS 只读拉音频 | MPS + CPU |
| K8s staging | CUDA 镜像 + PVC/mock 或真实权重 | 发布前验收、checksum/canary | PVC / 内网制品库 | NVIDIA CUDA |
| K8s prod | 分 profile CUDA 镜像 + 只读模型 PVC | 生产 serving | approved model bundle | NVIDIA CUDA |

Mac 场景不是生产替代品，但它必须和生产共享同一套模型 registry、checksum 算法、fake/real runtime 开关和 warmup/health 语义。

### 5.5 当前流程模型清单

当前流程里已经出现的模型和 runtime 分三类：已接入生产开关、已在 pipeline 使用但仍是 deterministic、以及文档预留未接入。

| 能力 / 步骤 | 当前模型 / runtime | 当前接入状态 | 代码入口 | 备注 |
|---|---|---|---|---|
| ASR / `ASR` | Qwen3-ASR，fake 为 `deterministic-asr-v0` | 已接入 `Settings`、registry、worker runtime | `get_asr_runtime()`、`Qwen3AsrRuntime` | `AI_WORKER_USE_FAKE_ASR_RUNTIME=false` + `AI_WORKER_QWEN3_ASR_MODELS_DIR` 后走真实模型 |
| 说话人区分 / `DIARIZATION` | pyannote/speaker-diarization-3.1，fake 为 `single-speaker-v0` | 已接入 `Settings`、registry、worker runtime | `get_diarization_runtime()`、`PyannoteDiarizationRuntime` | 负责输出 `SPEAKER_00` 这类匿名 speaker turn，不等于识别具体人 |
| 声纹 embedding / `SPEAKER_EMBEDDING` | 3D-Speaker CAM++，fake 为 `deterministic-speaker-v0` | Stage 1 已接入 `Settings`、registry、worker runtime、warmup 和 checksum guard | `get_speaker_runtime()`、`CamPlusPlusRuntime` | 真实质量仍取决于 CAM++ 权重、有效片段筛选和授权 reference embedding 质量 |
| 声纹匹配 / `SPEAKER_MATCHING` | 余弦相似度匹配 authorized reference embeddings | 已接入流程，不是独立深度模型 | `AuthorizedScopeMatcher` | Java 决定授权范围并提供参考 embedding，Python 只在授权范围内匹配 |
| 文本 embedding / `RAG_INDEXING`、`/internal/embed` | BAAI/bge-m3，fake 为 `bge-m3-fake-v0` | 已接入 `Settings`、registry、worker runtime 和内部 API | `get_bge_m3()`、`BgeM3Runtime` | 用于 RAG chunk embedding 和同步 query embedding |
| Rerank / `/internal/rerank` | BAAI/bge-reranker-v2-m3，fake 为 `bge-reranker-v2-m3-fake-v0` | 已接入 `Settings`、registry、内部 API | `get_bge_reranker()`、`BgeRerankerRuntime` | Java 完成权限过滤后同步调用 ai-worker rerank |
| Forced Alignment | Qwen3-ForcedAligner-0.6B 或等价模型 | 仅文档预留，当前 pipeline 仍走轻量时间戳/merge | 暂无生产 runtime | 后续只对精确引用片段按需启用 |

结论：当前实际链路已完整覆盖 ASR、说话人区分、声纹步骤、文本 embedding 和 rerank；Stage 1 后 CAM++ 也进入统一 registry/env/checksum/warmup 体系。后续 speaker 优化重点不再是“是否接入”，而是“是否稳定且足够准”：有效片段选择、embedding 质心、top-K 阈值、CPU/MPS/CUDA 路由和授权 reference embedding 的质量闭环。

## 6. 模型权重供应链

### 6.1 准入流程

每个模型进入 staging/prod 前必须完成：

1. 下载权重到隔离构建环境。
2. 下载并保存 license/model card。
3. 记录来源 URL、commit/revision、下载时间、下载人。
4. 计算 checksum，使用 `ai_worker.observability.model_checksum.compute_checksum()`。
5. 上传内网制品库，例如 `nexus://models/<model>/<version>/`。
6. 更新 `docs/model-registry.md` 或同步到 `model_registry` 表。
7. 审批状态变为 approved 后才能进入 prod overlay。

### 6.2 Bundle 结构

推荐每个模型 bundle 包含：

```text
MODEL_MANIFEST.json
LICENSE
MODEL_CARD.md
checksums.txt
<weight files>
<tokenizer/config files>
```

`checksums.txt` 保存单文件 hash，`MODEL_MANIFEST.json` 保存目录级 hash。运行时 readiness 使用目录级 hash，因为当前代码已经用 `compute_checksum(models_dir)`。

### 6.3 分发策略

推荐优先级：

1. **只读 PVC 预加载**: 适合 Kubernetes staging/prod，模型目录由独立 Job 同步。
2. **节点本地 cache + DaemonSet 同步**: 适合大模型、多个 Pod 共享同一 GPU 节点时降低 PVC I/O。
3. **initContainer 同步到 emptyDir**: 只适合 staging 或小模型，生产大模型不建议每次 Pod 启动下载。

主容器不负责下载权重。主容器只做读取、校验、加载、推理。

### 6.4 Init 校验

initContainer 建议只做：

- 检查目录存在。
- 检查核心文件存在。
- 计算目录 checksum。
- 和 expected checksum 比较。
- 输出结构化日志。

initContainer 不建议做：

- 联网下载权重。
- 修改模型目录。
- 自动修复 checksum mismatch。

## 7. 镜像构建方案

### 7.1 构建矩阵

| 镜像 tag 后缀 | `BASE` | `UV_EXTRAS` | 构建策略 |
|---|---|---|---|
| `api-cpu` | `python:3.11-slim` | 空 | 可允许 dev fallback resolve |
| `bge-cuda` | `nvidia/cuda:13.0.0-cudnn-runtime-ubuntu22.04` | `real-bge` | frozen only |
| `asr-cuda` | 同上 | `real-asr` | frozen only |
| `diar-cuda` | 同上 | `real-diarization` | frozen only |
| `speaker-cuda` | 同上 | `real-speaker` | frozen only |
| `all-cuda` | 同上 | `real-models` | frozen only |

命名建议：

```text
ai-worker:<git_sha>-api-cpu-py311
ai-worker:<git_sha>-bge-cuda13-py311
ai-worker:<git_sha>-asr-cuda13-py311
ai-worker:<git_sha>-diar-cuda13-py311
ai-worker:<git_sha>-speaker-cuda13-py311
ai-worker:<git_sha>-all-cuda13-py311
```

### 7.2 Lock 策略

- `UV_EXTRAS` 为空时，可以保留当前 Dockerfile 的 dev fallback，便于本地开发。
- `UV_EXTRAS` 为 `real-*` 时必须 `uv sync --frozen --no-dev`，失败就停止构建。
- `uv.lock` 更新必须在代码评审中显式出现。
- CUDA base、torch、torchaudio、funasr、pyannote、FlagEmbedding、modelscope/CAM++ 的兼容关系必须在构建日志和 release manifest 中可追溯。

### 7.3 镜像 smoke test

每个镜像构建后至少做：

| 镜像 | 检查 |
|---|---|
| `api-cpu` | `python -c "import ai_worker"`，`/internal/health` |
| `bge-cuda` | `import FlagEmbedding`，`GET /internal/hardware` 显示 package true |
| `asr-cuda` | `import funasr`，`GET /internal/hardware` 显示 package true |
| `diar-cuda` | `import pyannote.audio`，`GET /internal/hardware` 显示 package true |
| `speaker-cuda` | `import modelscope`，CAM++ runtime import 成功 |
| `all-cuda` | 上述全部 package true |

smoke test 不需要加载真实权重；真实权重加载在部署 smoke 阶段验证。

## 8. Kubernetes 部署方案

### 8.1 Base 和 profile 拆分

当前只有一个 `ai-worker` StatefulSet。建议演进为：

```text
infra/meeting-infra/k8s/base/ai-worker/
  common-config.yaml
  service.yaml
  profiles/
    api-cpu.yaml
    bge-gpu.yaml
    asr-gpu.yaml
    diar-gpu.yaml
    speaker-gpu.yaml
    all-gpu.yaml
```

如果短期不重构目录，也可以先用 Kustomize patches 表达 profile。

### 8.2 Profile 配置矩阵

| Env | `api-cpu` | `bge-gpu` | `asr-gpu` | `diar-gpu` | `speaker-gpu` | `all-gpu` |
|---|---|---|---|---|---|---|
| `AI_WORKER_MODEL_PROFILE` | `api` | `bge` | `asr` | `diar` | `speaker` | `all` |
| `AI_WORKER_RABBITMQ_TASK_QUEUES` | 空 | `embed-queue,rerank-queue` | `gpu-asr-queue` | `gpu-diar-queue` | `gpu-speaker-queue` | 当前全集 |
| `AI_WORKER_USE_FAKE_RUNTIME` | `true` | `false` | `true` 或不使用 | `true` 或不使用 | `true` 或不使用 | `false` |
| `AI_WORKER_USE_FAKE_ASR_RUNTIME` | `true` | `true` | `false` | `true` | `true` | `false` |
| `AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME` | `true` | `true` | `true` | `false` | `true` | `false` |
| `AI_WORKER_USE_FAKE_SPEAKER_RUNTIME` | `true` | `true` | `true` | `true` | `false` | `false` |

BGE profile 只需要配置 bge/reranker 目录和 checksum。ASR profile 只需要配置 qwen3-asr。Diar profile 只需要配置 pyannote。Speaker profile 只需要配置 CAM++ 目录和 checksum。

### 8.3 Probe 策略

- `startupProbe`: `/internal/health`，给真实模型镜像和底层 CUDA 初始化留足时间。
- `livenessProbe`: `/internal/health`，只检查进程是否可响应。
- `readinessProbe`: `/internal/ready`，检查模型状态、checksum、required package。

readiness 不应该因为 RabbitMQ 短暂断开而失败；模型服务是否能接计算任务才是这里的重点。

### 8.4 Volume 策略

- `/opt/models`: 只读 PVC 或节点 cache，只读挂载。
- `/app/.artifacts`: `emptyDir`，必须设置 `sizeLimit`。
- `/var/lib/ai-worker/enrollment`: 保持当前 enrollment PVC 或小容量 RWO PVC。

建议：

```yaml
volumes:
  - name: artifacts
    emptyDir:
      sizeLimit: 20Gi
```

实际大小按 ASR 中间产物、转码输出和单 Pod 最大任务数压测后确定。

### 8.5 资源建议

初始建议：

| Profile | CPU request | Memory request | GPU | 备注 |
|---|---:|---:|---:|---|
| `api-cpu` | 200m | 512Mi | 0 | dev/BFF |
| `bge-gpu` | 1000m | 6Gi | 1 | batch size 后续压测 |
| `asr-gpu` | 2000m | 12Gi | 1 | 长音频主要瓶颈 |
| `diar-gpu` | 2000m | 12Gi | 1 | pyannote 内存峰值需压测 |
| `speaker-gpu` | 1000m | 4Gi | 0-1 | CAM++ 声纹 embedding，可先 CPU/MPS 验证再决定 GPU |
| `all-gpu` | 4000m | 16Gi+ | 1 | 仅验收或 fallback |

这些不是最终容量规划，只是部署拆分后的起点。最终值必须来自 RTF、显存峰值、队列深度和失败率。

## 9. Mac 本地部署方案

### 9.1 定位

Mac 本地部署是正式支持的开发和验收路径：

- 开发者在本机验证真实 BGE/rerank、ASR、diarization runtime。
- 演示环境中 Mac 作为 AI 推理机，远端 CentOS/ECS 跑 Java、RabbitMQ、PostgreSQL。
- 模型升级前先在 Mac 上做 smoke test、质量抽查和性能基线。

Mac 本地部署不是生产 serving 路径。生产仍以 Linux + NVIDIA + CUDA + K8s GPU 节点为准。

### 9.2 Mac 运行形态

| 形态 | 说明 | 适用场景 |
|---|---|---|
| `local-fake` | fake runtime，不加载真实权重 | 日常开发、单测、BFF/UI 调试 |
| `local-real-bge` | 只启用 BGE/rerank 真实模型 | RAG/embedding 调试 |
| `local-real-speaker` | 只启用声纹 embedding/matching 真实模型 | 声纹注册、候选匹配调试 |
| `local-real-audio` | 启用 ASR + diarization + speaker 真实模型 | 会议音频链路验收 |
| `local-all` | 全部真实模型 | 演示、两机联调、回归验收 |

本地运行应使用 `uv` 原生环境，不使用 Docker Desktop 承载真实模型。Docker Desktop 会抢占统一内存，也无法提供生产 CUDA 行为。

### 9.3 Mac 依赖和环境

要求：

- Apple Silicon，终端必须是 `arm64`，不能跑在 Rosetta。
- macOS 13+。
- Python 3.11、uv、ffmpeg、libsndfile、cmake、pkg-config。
- 真实模型建议物理内存 32GB+，模型目录可放到外置 SSD。

推荐目录：

```text
$HOME/meeting-models/
  bge-m3/v1/
  bge-reranker-v2-m3/v1/
  qwen3-asr-1.7b/v2026.05.1/
  pyannote/v3.1/
```

本地 `.env` 仍使用生产同款变量：

```bash
AI_WORKER_BGE_M3_MODELS_DIR=$HOME/meeting-models/bge-m3/v1
AI_WORKER_BGE_RERANKER_MODELS_DIR=$HOME/meeting-models/bge-reranker-v2-m3/v1
AI_WORKER_QWEN3_ASR_MODELS_DIR=$HOME/meeting-models/qwen3-asr-1.7b/v2026.05.1
AI_WORKER_PYANNOTE_MODELS_DIR=$HOME/meeting-models/pyannote/v3.1
AI_WORKER_CAM_PLUS_MODELS_DIR=$HOME/meeting-models/cam_plus/v1
AI_WORKER_USE_FAKE_RUNTIME=false
AI_WORKER_USE_FAKE_ASR_RUNTIME=false
AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false
AI_WORKER_USE_FAKE_SPEAKER_RUNTIME=false
```

### 9.4 Mac 设备路由

Apple Silicon 推荐默认路由：

| 模型 / 能力 | 推荐设备 | dtype | 理由 |
|---|---|---|---|
| BGE-m3 embedding | `mps` | `fp32` | FlagEmbedding 可受益于 MPS，fp16 在 Apple GPU 上风险更高 |
| BGE reranker | `mps` | `fp32` | rerank 延迟敏感，MPS 可减少交互等待 |
| Qwen3-ASR | `cpu` | `fp32` | funasr 在 MPS 上算子兼容性不稳定，优先稳定 |
| pyannote diarization | `cpu` | `fp32` | CPU 路径更可控，避免 MPS fallback 噪声和 crash |
| 3D-Speaker CAM++ | `cpu` 起步，验证后可尝试 `mps` | `fp32` | 声纹 embedding 模型较小，先以稳定和一致性为主 |

对应配置：

```bash
AI_WORKER_BGE_M3_DEVICE=mps
AI_WORKER_BGE_RERANKER_DEVICE=mps
AI_WORKER_ASR_DEVICE=cpu
AI_WORKER_DIARIZATION_DEVICE=cpu
AI_WORKER_SPEAKER_DEVICE=cpu
AI_WORKER_BGE_M3_DTYPE=fp32
AI_WORKER_BGE_RERANKER_DTYPE=fp32
```

如果 `/internal/hardware` 显示 MPS 不可用，BGE/rerank 自动降到 CPU，不能因为 MPS 缺失阻断本地开发。

### 9.5 两机联调

两机联调拓扑：

- Java/RabbitMQ/PostgreSQL/MinIO 或 OSS 在 CentOS/ECS。
- ai-worker 原生跑在 Mac。
- Mac 通过远端 RabbitMQ 消费任务，通过 HMAC 回调 Java。
- Worker 使用 OSS/TOS 只读凭据读取音频，不能复用 Java 写权限凭据。

关键约束：

- 双方地址不能写 `localhost`。
- RabbitMQ 不应裸露公网；优先 VPN、内网或 SSH tunnel。
- HMAC secret 必须与 Java 配置一致。
- Mac 侧 OSS/TOS endpoint 通常要用公网 endpoint，因为 Mac 不在云厂商 VPC 内。

### 9.6 Mac 本地验收

本地 smoke 顺序：

1. `uv run python -c "import ai_worker"`。
2. `uv run python -c "import torch; print(torch.backends.mps.is_available())"`。
3. 启动 `ai-worker-api`。
4. `GET /internal/hardware`，确认 torch/MPS/package 状态。
5. `GET /internal/models`，确认模型目录、checksum、status。
6. `POST /internal/models/warmup?capabilities=embedding,rerank`。
7. 对 30-60 秒音频跑 ASR/diarization smoke。
8. 记录 RTF、内存峰值、模型加载耗时。

Mac 本地验收失败不能直接推出生产不可用。需要区分：

- arm64 wheel 或 MPS 限制。
- 模型目录或 checksum 问题。
- Python 依赖版本问题。
- 真实业务音频质量问题。

## 10. 加速优化方案

### 10.1 总体策略

加速不只靠更大的 GPU。优先顺序：

1. **减少无效计算**: VAD、静音过滤、按需 alignment、RAG 异步入库。
2. **正确设备路由**: CUDA/MPS/CPU 按模型稳定性分配。
3. **批处理和分片**: embedding/rerank batch，ASR chunk，长音频窗口化。
4. **并发边界**: 单 GPU 默认串行，拆 profile 后再扩副本。
5. **缓存和复用**: 模型常驻、音频标准化产物缓存、embedding 去重。
6. **可观测压测**: 每次调参记录 RTF、p95、显存/内存峰值、失败率。

### 10.2 ASR 加速

推荐：

- 长音频先做音频标准化和 VAD。
- ASR chunk 控制在 30-120 秒，保留 0.3-0.8 秒 overlap。
- 失败重跑以 chunk 为单位，不重跑整场会议。
- 术语表、参会人姓名、会议标题作为 prompt/context 输入，减少后处理纠错。
- Forced Alignment 只对需要精确引用的片段执行，不对全量低价值片段做词级对齐。
- 记录 `processing_seconds / audio_seconds` 作为 RTF。

Mac 路径：

- Qwen3-ASR 默认 CPU。
- 本地 smoke 可以只跑 30-60 秒样本。
- 大于 30 分钟的真实会议优先送 CUDA staging/prod，不建议在 Mac 上等待完整链路。

CUDA 路径：

- ASR profile 单 Pod 单 GPU，初始并发 1。
- 如果显存和 RTF 稳定，再考虑多副本横向扩展，不先在单 Pod 内开高并发。

### 10.3 Diarization 加速

推荐：

- 如果用户填写参会人数，传 `num_speakers`。
- 不确定人数时传 `min_speakers` / `max_speakers`，降低搜索空间。
- 优先整段 diarization 保持 speaker 连续性；超长会议才考虑 20-30 分钟窗口化。
- 分段 diarization 必须补 speaker stitching，否则准确率和后处理复杂度会失控。
- 重叠说话比例高时降低自动认人置信度，不强行标注。

Mac 路径：

- 默认 CPU。
- 重点用于质量 smoke，不作为长会议吞吐基线。

CUDA 路径：

- 和 ASR 分 profile，避免两个大模型同 Pod 抢显存。

### 10.4 Embedding 和 Rerank 加速

推荐：

- embedding 输入按 batch 处理，默认 `16`，按 GPU/MPS 内存压测调到 `32` 或更高。
- rerank batch 独立配置，不和 embedding 共用同一个 batch size。
- 对相同 chunk 内容做 hash 去重，避免重复 embedding。
- embedding 入库异步化，不阻塞用户查看转录。
- RAG 查询时先粗召回，再 rerank top N，N 应按 p95 延迟和答案质量调参。

Mac 路径：

- BGE/rerank 默认 MPS + fp32。
- 不使用 fp16 作为默认值，除非具体版本在本机验证稳定。

CUDA 路径：

- BGE/rerank 可独立 profile 多副本扩容。
- batch size 必须作为可配置项进入 metrics 和日志，便于比较调参效果。

### 10.5 声纹识别加速

推荐：

- Diarization 输出 speaker turn 后，只对有效说话窗口提取声纹 embedding。
- 过短片段、低置信度片段和静音片段不进入声纹 embedding。
- 同一 speaker label 可先按多个高质量片段提取 embedding，再做均值或质心，减少单片段噪声。
- 声纹匹配只在 Java 授权范围内做 top K，不做全租户搜索。
- 注册音频的 reference embedding 和会议中的 speaker embedding 使用同一个模型版本和同一套归一化策略。
- 明文 embedding 只在 Python 内存和 HMAC 回调过程中短暂存在，Java 落库前做 KMS 信封加密。

Mac 路径：

- CAM++ 先以 CPU fp32 作为稳定基线。
- 因模型较小，可以优先优化片段选择和缓存，而不是先追 MPS/GPU。
- 本地验收重点看同一人多段音频 cosine 稳定性、不同人区分度和候选 top K 排序。

CUDA 路径：

- `speaker-gpu` profile 独立消费 `gpu-speaker-queue`。
- 如果 CAM++ CPU 已满足吞吐，可把 `speaker-gpu` 作为 CPU profile 部署，避免占用 ASR/diarization GPU。
- 只有当声纹队列成为瓶颈时，再把 speaker profile 迁到 GPU 节点。

### 10.6 I/O 和缓存优化

推荐：

- 标准化 WAV、VAD 结果、ASR 原始 JSON、diarization RTTM/JSON 都作为中间产物缓存。
- speaker turn 裁剪结果、speaker embedding artifact metadata 可缓存；明文 embedding 不落盘。
- 大 JSON 存对象存储，结构化 segment 存数据库。
- Mac 本地将模型目录放 SSD，避免外置慢盘导致 warmup 和首次推理抖动。
- K8s 使用只读 PVC 或节点 cache，避免每个 Pod 重复下载。
- 清理策略按任务 TTL 和磁盘水位执行，不让 `/app/.artifacts` 吃满节点磁盘。

### 10.7 量化和编译边界

短期默认不把量化和 `torch.compile` 作为生产基线：

- BGE/rerank 可以单独评估 fp16/CUDA、fp32/MPS 的质量和稳定性。
- ASR/diarization 不在 Mac 上默认启用 fp16。
- CAM++ 不先做量化，先验证真实模型接入、embedding 稳定性和候选匹配质量。
- 量化必须先通过质量回归，不允许只看延迟下降。
- `torch.compile`、ONNX、TensorRT、vLLM 后端可作为后续专项，不和 Stage 1 部署基线混在一起。

## 11. 运行时配置方案

建议新增或标准化以下配置：

| 配置 | 默认 | 说明 |
|---|---|---|
| `AI_WORKER_MODEL_PROFILE` | `all` | `api`, `bge`, `asr`, `diar`, `speaker`, `all` |
| `AI_WORKER_LOCAL_PROFILE` | 空 | `mac-fake`, `mac-bge`, `mac-speaker`, `mac-audio`, `mac-all`，只用于本地脚本和 runbook |
| `AI_WORKER_MODEL_WARMUP_ON_STARTUP` | `false` | 默认不阻塞 API 启动 |
| `AI_WORKER_MODEL_WARMUP_CAPABILITIES` | 空 | `embedding,rerank,asr,diarization,speaker` |
| `AI_WORKER_MODEL_LOAD_TIMEOUT_SECONDS` | `600` | warmup/首次加载上限 |
| `AI_WORKER_BGE_M3_BATCH_SIZE` | `16` | 当前 runtime 构造参数应 env 化 |
| `AI_WORKER_RERANK_BATCH_SIZE` | `16` | rerank batch |
| `AI_WORKER_ASR_MAX_CONCURRENCY` | `1` | 单 GPU 默认 1 |
| `AI_WORKER_DIARIZATION_MAX_CONCURRENCY` | `1` | 单 GPU 默认 1 |
| `AI_WORKER_SPEAKER_MAX_CONCURRENCY` | `1` | 声纹 embedding 默认并发 |
| `AI_WORKER_ASR_CHUNK_SECONDS` | `60` | ASR 默认 chunk 长度 |
| `AI_WORKER_ASR_CHUNK_OVERLAP_SECONDS` | `0.5` | ASR chunk overlap |
| `AI_WORKER_SPEAKER_MIN_SEGMENT_SECONDS` | `3` | 低于该长度的 speaker turn 不提声纹 |
| `AI_WORKER_SPEAKER_TOP_K` | `5` | 声纹候选返回上限 |
| `AI_WORKER_ENABLE_AUDIO_ARTIFACT_CACHE` | `true` | 缓存标准化音频和中间产物 |
| `AI_WORKER_MODEL_CACHE_DIR` | 空 | 仅 staging 使用，不作为生产在线下载目录 |

`AI_WORKER_MODEL_PROFILE` 应用于启动自检：

- profile 是 `bge`，但 `AI_WORKER_USE_FAKE_RUNTIME=true`，应 fail loud。
- profile 是 `asr`，但未配置 `AI_WORKER_QWEN3_ASR_MODELS_DIR`，应 fail loud。
- profile 是 `diar`，但镜像缺 `pyannote.audio`，readiness 应 503。
- profile 是 `speaker`，但 CAM++ 权重、checksum 或 `real-speaker` 依赖缺失，readiness 必须阻止进入 prod。
- local profile 是 `mac-audio`，但终端不是 arm64，应 fail loud。

## 12. Warmup 和流量接入

### 12.1 Warmup 模式

推荐三种 warmup 方式：

1. **部署后 Job 调用**: 部署完成后由 Job 调用 `POST /internal/models/warmup?capabilities=...`。
2. **人工/运维调用**: 用于 staging 和故障恢复。
3. **启动时可选 warmup**: 仅在小模型或确定冷启动可接受时开启。

默认不建议启动时阻塞加载所有模型。原因是 ASR/diarization 冷启动长，失败路径应该先通过 readiness 暴露，而不是导致进程反复启动。

### 12.2 接流量顺序

1. Pod 启动。
2. `startupProbe` 通过。
3. init 或运行时 checksum 校验通过。
4. `/internal/hardware` 显示 required packages 和 GPU 状态正确。
5. 调用 warmup。
6. `/internal/models` 全部目标模型 READY。
7. `/internal/ready` 返回 200。
8. 开始消费对应队列或接同步 rerank 请求。

### 12.3 失败处理

- warmup 失败不应返回 liveness 失败。
- checksum mismatch 不自动修复。
- required package 缺失提示重建镜像，而不是让任务首次推理时才失败。
- CUDA OOM 仍应记录指标并退出，由平台重启；如果反复 OOM，应降 batch 或拆 profile。

## 13. 灰度和回滚

### 13.1 模型版本升级流程

1. 新模型 bundle 进入内网制品库。
2. 更新 `docs/model-registry.md` 或 `model_registry`。
3. 同步新权重到 PVC 新目录。
4. 新建或更新 ConfigMap/Secret 指向新目录和 checksum。
5. 创建新 profile 副本或 canary overlay。
6. 执行 hardware、models、ready、warmup 检查。
7. 让少量测试 tenant 或低优先级任务进入新 profile。
8. 观察质量、RTF、显存、失败率。
9. 扩大副本或切换队列。

### 13.2 回滚原则

- 不删除新权重，只停止使用。
- 不覆盖旧权重，旧版本目录必须保留到观察期结束。
- 回滚只切回旧 ConfigMap、Secret、image tag 或 deployment selector。
- 如果是镜像依赖问题，回滚镜像即可；如果是模型质量问题，回滚模型目录和 checksum。

### 13.3 Canary 路由

短期可用队列隔离实现 canary：

- `gpu-asr-queue-canary`
- `gpu-diar-queue-canary`
- `gpu-speaker-queue-canary`
- `embed-queue-canary`

Java 侧按 tenant、会议 ID 或任务优先级选择投递到 canary 队列。没有 Java 路由支持前，可以先在 staging 完成 canary，不强行进入 prod。

## 14. 可观测性

### 14.1 指标

需要新增或补齐：

| 指标 | 标签 | 说明 |
|---|---|---|
| `ai_worker_model_load_duration_seconds` | `model,status` | 模型加载耗时 |
| `ai_worker_model_warmup_total` | `model,status` | warmup 次数 |
| `ai_worker_model_checksum_mismatch_total` | `model` | checksum mismatch |
| `ai_worker_model_inference_duration_seconds` | `model,operation,status` | 推理耗时 |
| `ai_worker_model_inference_concurrency` | `device,model` | 当前并发 |
| `ai_worker_model_rtf` | `step` | 已存在，可继续用于 ASR/diarization |
| `ai_worker_gpu_memory_used_bytes` | `device` | 已存在 |
| `ai_worker_gpu_utilization_percent` | `device` | 已存在 |
| `ai_worker_audio_chunk_seconds` | `step` | ASR/diarization 分片长度 |
| `ai_worker_model_batch_size` | `model,operation` | 当前 batch size |
| `ai_worker_speaker_segments_selected_total` | `status` | 声纹 embedding 片段选择结果 |
| `ai_worker_speaker_match_candidates_total` | `status` | 声纹候选匹配结果 |

### 14.2 日志字段

模型部署相关日志必须包含：

- `profile`
- `model`
- `model_version`
- `models_dir`
- `expected_checksum`
- `observed_checksum`
- `device`
- `dtype`
- `queue`
- `task_id`
- `trace_id`
- `batch_size`
- `chunk_seconds`
- `rtf`
- `speaker_label`
- `speaker_top_k`
- `speaker_min_segment_seconds`

checksum 和具体路径不要作为 Prometheus 高基数字段，但应进入结构化日志和发布记录。

### 14.3 告警

初始告警：

- readiness 持续 503。
- checksum mismatch。
- model load failure。
- warmup failure。
- CUDA OOM exit。
- GPU memory > 90% 持续 10 分钟。
- ASR/diarization RTF 连续恶化。
- speaker embedding failure rate 或候选为空比例异常。
- 队列 lag 超阈值。
- Mac 本地 smoke 的 MPS 不可用或 package 缺失。

## 15. 安全和合规

1. 模型权重制品库只允许基础设施账号写入，运行时 Pod 只读。
2. 权重 PVC 只读挂载到主容器。
3. 模型准入必须记录 license、来源、审批人和审批日期。
4. 不在日志里输出访问令牌、制品库凭据、OSS/TOS 密钥。
5. ai-worker 继续使用非 root 用户运行。
6. Secret 不应保存在 base manifest，继续由 overlay/operator/ExternalSecret 管理。
7. 生产 HuggingFace/Transformers offline env 必须保持开启。
8. Mac 本地下载真实权重时也要遵守 license 和模型条款，下载目录不能提交到 Git。
9. Mac 两机联调只能使用 worker 专用只读 OSS/TOS 凭据。

## 16. 测试和验收

### 16.1 本地和 CI

- `uv run pytest tests/test_models_endpoint.py -q`
- `uv run pytest tests/test_model_checksum.py -q`
- `uv run pyright ai_worker/`
- `docker build` 各 profile 镜像。
- 镜像 smoke test 检查 required package。

### 16.2 Mac 本地

- `uname -m` 返回 `arm64`。
- `uv run python -c "import torch; print(torch.backends.mps.is_available())"`。
- `GET /internal/hardware` 显示 MPS/CPU/package 状态。
- `GET /internal/models` 显示模型目录和 checksum。
- `POST /internal/models/warmup?capabilities=embedding,rerank` 成功。
- `POST /internal/models/warmup?capabilities=speaker` 可触发 CAM++ fake/real runtime 预热并反映到 `/internal/models`。
- 30-60 秒音频 smoke 记录 ASR/diarization RTF 和 speaker candidates。
- 两机联调时验证 RabbitMQ 消费、OSS/TOS 只读拉取和 HMAC 回调。

### 16.3 Staging

- 使用 staging mock weights 跑 checksum guard。
- 使用真实权重跑 `/internal/hardware`。
- 对每个 profile 调用 `/internal/models/warmup?capabilities=...`。
- 跑小样本音频任务，记录 ASR/diarization RTF、speaker embedding 耗时和候选命中情况。
- 故意改一个 staging 权重字节，确认 readiness 503。

### 16.4 Prod 发布前

必须记录：

- image digest。
- model path。
- expected checksum。
- `/internal/hardware` 响应摘要。
- `/internal/models` 响应摘要。
- warmup 结果。
- 回滚目标版本。

## 17. 阶段计划

### Stage 1: 部署基线

目标：在不拆 Pod 的前提下，让现有真实模型部署和 Mac 本地部署都可重复。

任务：

- 补齐真实模型 checksum 和审批记录。
- 在 overlay/Secret 接入 `AI_WORKER_*_EXPECTED_CHECKSUM`。
- 给 `artifacts` emptyDir 加 `sizeLimit`。
- 给 StatefulSet 加 `startupProbe`。
- 写模型同步和校验 runbook。
- 更新 Mac 本地部署 runbook，明确 `mac-fake`、`mac-bge`、`mac-audio`、`mac-all`。
- 明确声纹当前状态：deterministic fake 和 CAM++ real 已进入同一 registry/worker/warmup/checksum 合约，真实质量验收另拆 stage。
- 固化发布前检查命令。

### Stage 2: Mac 加速基线

目标：让 Mac 本地真实模型调试有明确设备路由和性能基线。

任务：

- 固化 Mac 默认设备路由：BGE/rerank=MPS fp32，ASR/diarization=CPU fp32。
- 固化 Mac 声纹默认路由：CAM++=CPU fp32 起步。
- 增加 Mac smoke checklist 和 RTF 记录模板。
- 将 BGE/rerank batch size env 化。
- 将 ASR chunk 参数 env 化。
- 将 speaker 最短片段、top K、并发参数 env 化。
- 为 30 秒、5 分钟、30 分钟样本建立本地性能基线。

### Stage 3: 镜像矩阵

目标：CI/deploy 能产出按能力拆分的镜像。

任务：

- 增加 `api-cpu`、`bge-cuda`、`asr-cuda`、`diar-cuda`、`speaker-cuda`、`all-cuda` 构建目标。
- real-* 构建强制 frozen lock。
- 每个镜像增加 package smoke test。
- release manifest 记录 image digest。

### Stage 4: K8s Profile 拆分

目标：BGE、ASR、diarization、speaker 独立部署、独立扩缩容。

任务：

- 拆 profile manifests 或 Kustomize patches。
- 每个 profile 绑定对应队列。
- 每个 profile 只挂载需要的模型目录。
- 每个 profile 配置独立 resources。
- speaker profile 先允许 CPU-only 部署，压测后再决定是否占 GPU。
- `all-gpu` 作为 fallback，不作为默认生产 profile。

### Stage 5: 模型灰度发布

目标：模型版本升级有 canary、指标和回滚。

任务：

- 新旧模型目录并存。
- 支持 canary 队列或 staging canary。
- 发布记录绑定模型版本和 image digest。
- warmup job 自动化。
- Grafana/Prometheus 加模型部署面板和告警。

### Stage 6: 深度加速专项

目标：在部署基线稳定后再做更激进的性能优化。

任务：

- 评估 ASR backend、batch/async 服务化和更细粒度 chunk 调度。
- 评估 BGE/rerank fp16/CUDA、ONNX 或 TensorRT。
- 评估 diarization 分段并行和 speaker stitching。
- 评估 CAM++ CPU/MPS/CUDA 路由、片段选择策略和 embedding 质心策略。
- 对每项加速建立质量回归，不只比较延迟。

## 18. 风险和取舍

| 风险 | 影响 | 处理 |
|---|---|---|
| 镜像矩阵增加构建时间 | CI 变慢 | 只在 release 分支或模型相关变更构建 real-* |
| 多 profile 增加 K8s manifests 复杂度 | 运维成本上升 | 先用 Kustomize patches，稳定后再抽 components |
| 权重同步慢 | 发布窗口变长 | 提前同步到新目录，发布时只切配置 |
| checksum 计算慢 | Pod 启动慢 | init 校验可缓存单文件 hash；目录 hash 只在发布和 readiness 首次计算 |
| ASR/diarization 分拆后任务编排复杂 | Java 投递队列需配合 | Stage 4 前先完成 Stage 1/2/3，减少同时变更面 |
| all-gpu fallback 依赖仍重 | 镜像大、显存高 | 只保留验收和应急，不作为默认生产 |
| Mac MPS 算子不完整 | 本地真实模型 crash 或 fallback | BGE/rerank 才默认 MPS，ASR/diarization 默认 CPU |
| Mac 内存不足 | 进程被系统杀掉 | 提供 fake/bge/audio/all profile，长会议送 CUDA staging |
| 分片加速影响准确率 | ASR 边界错字或 speaker stitching 错误 | chunk overlap、质量回归和可回滚参数 |
| CAM++ 权重或依赖缺失 | speaker profile 无法进入真实声纹服务 | readiness、checksum、`real-speaker` 镜像构建和 staging mock fixtures 一起拦截，不把 deterministic 当生产模型 |
| 量化导致质量下降 | 召回、rerank 或转录质量退化 | 量化只能进入深度加速专项，必须过质量评测 |

## 19. 后续文档拆分

从本文拆六个 Superpowers implementation plan：

1. `2026-06-18-ai-worker-model-deploy-stage1-baseline.md`
2. `2026-06-18-ai-worker-model-deploy-stage2-mac-acceleration-baseline.md`
3. `2026-06-18-ai-worker-model-deploy-stage3-image-matrix.md`
4. `2026-06-18-ai-worker-model-deploy-stage4-profile-split.md`
5. `2026-06-18-ai-worker-model-deploy-stage5-rollout.md`
6. `2026-06-18-ai-worker-model-deploy-stage6-deep-acceleration.md`

Stage 1 完成前，不建议先拆 profile。模型权重、checksum、probe、warmup 和 Mac 本地 smoke 基线不稳时，过早拆成多个 Pod 会让排障成本显著上升。
