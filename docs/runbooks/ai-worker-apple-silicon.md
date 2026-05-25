# Apple Silicon 上的 ai-worker 部署运行手册

本文档说明如何在 Apple Silicon Mac 上原生运行 `ai-worker`，用于本地开发、
验收前验证和演示。该路径会在 arm64 macOS 上安装 `real-models` 依赖，
BGE embedding/rerank 使用 MPS，ASR 和说话人分离使用 CPU。脚本入口以
`deploy/ai-worker-apple-silicon.sh` 为准。

重要边界：这不是生产 serving 路径。生产 ai-worker 必须使用
`deploy/DEPLOY.md` §5.5.2.A 中的 Linux + NVIDIA + CUDA 镜像和 GPU 节点池。

## 0. 部署决策

| 目标 | 推荐路径 | 说明 |
|------|----------|------|
| 快速本地 worker 冒烟 | `./deploy/ai-worker-apple-silicon.sh stage && ./deploy/ai-worker-apple-silicon.sh run` | 使用确定性的 mock 权重，启动快。 |
| 本地真实模型演示 | `HF_TOKEN=... ./deploy/ai-worker-apple-silicon.sh weights && ./deploy/ai-worker-apple-silicon.sh run` | 需要磁盘、内存、网络和模型 license。 |
| 本地 Java + Mac 原生 worker 联调 | `meeting-api` 使用 `AI_WORKER_BASE_URL=http://host.docker.internal:8090`，worker 在 Mac 原生运行 | Compose 已支持该覆盖。 |
| Mac worker 连接 CentOS Java | Mac `.env` 指向 `http://<centos-ip>:8080`，CentOS Java 的 `AI_WORKER_BASE_URL` 指向 `http://<mac-ip>:8090` | 需要双向网络和一致的 HMAC。 |
| K8s / 生产 | 不使用本文档路径 | 使用 CUDA 镜像、GPU 节点池和生产模型卷。 |

Apple Silicon 路径适合验证集成逻辑、HMAC callback、模型 wiring、
readiness、checksum guard 和 UI workstation 流程。不适合做生产吞吐、
延迟或 SLO 评估。

## 0.1 生产边界和交接要求

本文档可以为生产 readiness 提供验证证据，但不能成为生产 runtime。生产
ai-worker serving 必须使用 Linux + NVIDIA + CUDA 路径。

Apple Silicon 路径允许做的事情：

| 用途 | 是否允许 | 说明 |
|------|----------|------|
| 本地真实模型联调 | 允许 | 验证 meeting-api 和 ai-worker 的请求、响应、HMAC。 |
| 真实 BGE/ASR/diarization 演示 | 允许 | 性能数据不能当生产 SLO。 |
| 模型 checksum 预演 | 允许 | 审核后可把 checksum 写入生产模型登记表。 |
| 生产 K8s serving | 禁止 | 必须使用 CUDA 镜像、GPU 节点池、生产模型卷。 |
| 生产性能基准 | 禁止 | MPS/CPU 行为不能代表 CUDA 吞吐。 |

从本文档交接给生产时，只交接验证结果，不复制 Mac 环境：

| 交接项 | 生产用途 |
|--------|----------|
| 模型名称和版本 | 填入生产模型 registry 或 PVC 目录规划。 |
| SHA-256 checksum | 设置生产 `AI_WORKER_*_EXPECTED_CHECKSUM=sha256:...`。 |
| 合同/接口验证结果 | 证明 meeting-api 和 ai-worker payload、HMAC 兼容。 |
| 音频冒烟样本 | 上生产前必须在 CUDA worker 上重跑。 |
| license 记录 | 确认部署账号已接受 pyannote/HuggingFace 条款。 |

不要把 `deploy/.ai-worker-apple-silicon.env` 复制到生产。该文件包含本地
URL、本地 RabbitMQ 凭据和本地生成的 HMAC/JWT。生产 Secret 必须来自
Vault、ExternalSecrets、SealedSecrets 或目标平台的 Secret Manager。

生产 ai-worker 必须满足：

| 要求 | 生产值 |
|------|--------|
| 镜像 | `ai-worker:cuda-<release>` 或等价 digest |
| 节点池 | NVIDIA GPU 节点，并满足 StatefulSet 的 nodeSelector |
| 运行时开关 | `AI_WORKER_USE_FAKE_RUNTIME=false`、`AI_WORKER_USE_FAKE_ASR_RUNTIME=false`、`AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false` |
| 离线模式 | 模型权重预置后设置 `HF_HUB_OFFLINE=1`、`TRANSFORMERS_OFFLINE=1` |
| 模型卷 | `/opt/models/<model>/<version>/`，且 checksum 匹配 |
| Secret | `ai-worker-secret` 在 `kubectl apply` 前已同步 |
| 硬件检查 | `/internal/hardware` 显示 CUDA 可用，不使用 MPS |
| readiness | `/internal/ready` 在 checksum 校验后返回 200 |

生产阻断项：

| 阻断项 | 动作 |
|--------|------|
| worker 运行在 MPS 或 CPU | 停止，改部署 CUDA 镜像到 GPU 节点池。 |
| `AI_WORKER_USE_FAKE_*` 为 true | 停止，fake runtime 不能接生产流量。 |
| 模型 checksum 缺失或不匹配 | 停止，重新 stage 权重并更新期望 checksum。 |
| 部署账号未接受 pyannote license | 停止，完成 license 审批后再 promote。 |
| 生产清单引用 Mac `.env` 文件 | 停止，替换为生产 Secret Manager 值。 |
| `/internal/hardware` 显示 `cuda.available=false` | 停止，检查调度、驱动、镜像或 torch/CUDA 构建。 |

## 1. 从 0 到 1 部署步骤

这一节按全新 Apple Silicon Mac 从零开始编写。目标是先跑通
`ai-worker` 本地服务，再按需要接入 `meeting-api` 做端到端联调。

### 1.1 确认机器和系统

```bash
sw_vers
uname -m
df -h "$HOME"
```

必须满足：

| 项 | 要求 | 原因 |
|----|------|------|
| 硬件 | Apple Silicon，`uname -m` 必须是 `arm64` | x86_64 macOS 不支持该真实模型路径。 |
| macOS | 13+ | MPS 和 Python wheel 兼容性更稳定。 |
| 磁盘 | 建议 100 GB 空闲 | 模型权重和 uv cache 可能接近 80 GB。 |
| 内存 | 建议 32 GB，冒烟最低 16 GB | ASR、diarization、浏览器、Java 会共同占用统一内存。 |
| 网络 | 可访问 HuggingFace | 下载 BGE 和 pyannote 权重需要。 |

如果 `uname -m` 不是 `arm64`，不要继续执行本文档。Linux + NVIDIA 生产路径
看 `deploy/DEPLOY.md`，Intel macOS 只能使用 fake runtime 或远端 CUDA worker。

### 1.2 安装 Xcode Command Line Tools 和 Homebrew

如果机器尚未安装命令行工具：

```bash
xcode-select --install
```

如果尚未安装 Homebrew：

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
eval "$(/opt/homebrew/bin/brew shellenv)"
brew update
```

确认 Homebrew 运行在 arm64 路径：

```bash
which brew
brew config | grep -E 'HOMEBREW_PREFIX|CPU|Rosetta'
```

Apple Silicon 正常前缀应为 `/opt/homebrew`。如果看到 Rosetta 或 `/usr/local`
路径，先修正 shell 环境，避免安装到 x86_64 工具链。

### 1.3 安装 ai-worker 本地依赖

worker 本身需要 Python/uv/ffmpeg/native build 依赖：

```bash
brew install git uv python@3.11 ffmpeg cmake pkg-config libsndfile jq openssl
```

如果还要和 `meeting-api` Compose 联调，需要安装并启动一个容器运行时。任选一种：

| 方案 | 安装 | 说明 |
|------|------|------|
| Docker Desktop | `brew install --cask docker` | 最直接，启动 Docker Desktop 后再跑 Compose。 |
| OrbStack | `brew install --cask orbstack` | Mac 上较轻量，Docker socket 兼容性好。 |
| Colima | `brew install colima docker docker-compose` | 需要手工 `colima start`，适合不装 Docker Desktop。 |

只跑原生 `ai-worker` 时不需要 Docker；只有联调 Java Compose 时才需要。

### 1.4 拉取代码

```bash
git clone https://github.com/JAHSEH618/meeting.git
cd meeting
git status --short
```

确认脚本可执行：

```bash
test -x deploy/ai-worker-apple-silicon.sh
./deploy/ai-worker-apple-silicon.sh
```

脚本不带参数会打印 usage，并以 64 退出，这是正常行为。

### 1.5 确认 Python 和 uv 是 arm64

```bash
python3 --version
uv --version
file "$(python3 -c 'import sys; print(sys.executable)')"
```

`file` 输出必须包含 `arm64`。如果是 `x86_64`，不要继续安装依赖，先修正
shell PATH 或重新安装 arm64 Python。

### 1.6 选择模型目录

默认模型目录是：

```bash
export AI_WORKER_MODELS_ROOT="$HOME/meeting-models"
```

如果系统盘空间不足，放到外置盘或大容量卷：

```bash
export AI_WORKER_MODELS_ROOT="/Volumes/models/meeting-models"
mkdir -p "$AI_WORKER_MODELS_ROOT"
```

后续所有 `stage`、`weights`、`run` 都会读取这个目录。不要频繁切换，否则
readiness 会报 missing path 或 checksum mismatch。

### 1.7 最短 0-1：mock 权重冒烟

这个路径不下载真实模型，适合确认脚本、依赖安装、模型目录、checksum 和 API
启动流程是否通。

终端 A：

```bash
cd meeting
export AI_WORKER_MODELS_ROOT="${AI_WORKER_MODELS_ROOT:-$HOME/meeting-models}"

./deploy/ai-worker-apple-silicon.sh stage
./deploy/ai-worker-apple-silicon.sh env

set -a
. deploy/.ai-worker-apple-silicon.env
. deploy/.ai-worker-apple-silicon.env.checksums
set +a

./deploy/ai-worker-apple-silicon.sh run
```

终端 B：

```bash
cd meeting
./deploy/ai-worker-apple-silicon.sh verify
```

成功标准：

| 检查 | 期望 |
|------|------|
| `stage` | 生成 `deploy/.ai-worker-apple-silicon.env.checksums` |
| `env` | 生成或复用 `deploy/.ai-worker-apple-silicon.env` |
| `run` | 完成 `uv sync --extra dev --extra real-models` 并监听 `:8090` |
| `verify` | `/internal/hardware` 和 `/internal/ready` 都能返回 JSON |

mock 权重只验证流程和 readiness，不代表真实模型推理质量。

### 1.8 真实模型 0-1

真实模型路径用于本地演示和生产前集成验证。执行前先确认：

1. HuggingFace 能访问。
2. 有 `HF_TOKEN`。
3. 部署账号已接受 pyannote license：
   `https://huggingface.co/pyannote/speaker-diarization-3.1`

下载权重：

```bash
cd meeting
export AI_WORKER_MODELS_ROOT="${AI_WORKER_MODELS_ROOT:-$HOME/meeting-models}"
HF_TOKEN=hf_xxx ./deploy/ai-worker-apple-silicon.sh weights
```

预热 Qwen3-ASR：

```bash
cd apps/ai-worker
export AI_WORKER_MODELS_ROOT=${AI_WORKER_MODELS_ROOT:-$HOME/meeting-models}
uv run --extra real-asr python - <<'PY'
from funasr import AutoModel
import os
root = os.environ["AI_WORKER_MODELS_ROOT"]
AutoModel(
    model="paraformer-zh",
    cache_dir=f"{root}/qwen3-asr-1.7b/v2026.05.1",
)
PY
cd ../..
```

启动真实模型 worker：

```bash
./deploy/ai-worker-apple-silicon.sh env
export AI_WORKER_MODELS_ROOT="${AI_WORKER_MODELS_ROOT:-$HOME/meeting-models}"
AI_WORKER_OFFLINE=1 ./deploy/ai-worker-apple-silicon.sh run
```

另一个终端验证：

```bash
./deploy/ai-worker-apple-silicon.sh verify
```

只有权重完整下载后才设置 `AI_WORKER_OFFLINE=1`。如果首次运行还需要懒加载，
先不加 offline，让 HuggingFace/funasr 完成缓存。

### 1.9 与 meeting-api 从 0 联调

这一步需要 Docker Desktop、OrbStack 或 Colima 已经启动。

终端 A：准备共享 HMAC 和依赖。

```bash
cd meeting
./deploy/ai-worker-apple-silicon.sh env

set -a
. deploy/.ai-worker-apple-silicon.env
set +a

docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
```

终端 B：启动 Mac 原生 ai-worker。

```bash
cd meeting
set -a
. deploy/.ai-worker-apple-silicon.env
set +a

./deploy/ai-worker-apple-silicon.sh run
```

终端 A：启动 Java，并把容器内的 meeting-api 指向 Mac 宿主机上的 worker。

```bash
AI_WORKER_BASE_URL=http://host.docker.internal:8090 \
  docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile full-stack up -d meeting-api
```

验证联调：

```bash
curl -fsSL http://localhost:8090/internal/hardware | jq .
curl -fsSL http://localhost:8090/internal/ready | jq .
curl -fsSL http://localhost:8080/actuator/health | jq '.components.aiWorker'
```

联调成功标准：

| 检查 | 期望 |
|------|------|
| ai-worker hardware | `mps.available=true`，BGE 为 `mps/fp32`，ASR/diarization 为 `cpu/fp32` |
| ai-worker readiness | 返回 200 |
| Java health | `components.aiWorker.status` 为 `UP` |
| HMAC | Java 日志和 worker 日志没有 callback/internal HMAC 拒绝 |

### 1.10 跨机器连接 CentOS 上的 meeting-api

如果 `meeting-api` 已经部署在 CentOS 上，而 `ai-worker` 在 Apple Silicon Mac
上原生运行，两者不在同一台机器，不能使用 `localhost` 或
`host.docker.internal` 表示对方。必须同时配置两条跨机器链路：

| 方向 | 需要能访问 | 用到的配置 |
|------|------------|------------|
| Mac ai-worker -> CentOS Java | `http://<centos-ip-or-domain>:8080` | `AI_WORKER_MEETING_API_BASE_URL`、`AI_WORKER_JAVA_API_BASE_URL` |
| Mac ai-worker -> CentOS RabbitMQ | `<centos-ip-or-vpn-name>:5672` | `AI_WORKER_RABBITMQ_HOST` / `PORT` / `USERNAME` / `PASSWORD` |
| CentOS Java -> Mac ai-worker | `http://<apple-mac-ip-or-vpn-name>:8090` | Java 侧 `AI_WORKER_BASE_URL` |
| 双向 HMAC | 两边完全一致 | `AI_WORKER_CALLBACK_HMAC_SECRET`、`AI_WORKER_INTERNAL_API_HMAC_SECRET` |

推荐使用同一内网、Tailscale/WireGuard/VPN 或安全组白名单。不要把 RabbitMQ
`5672` 对公网开放；至少只允许 Mac 的固定 IP 或 VPN IP 访问。

CentOS 防火墙示例，只允许 Mac 访问 Java 和 RabbitMQ：

```bash
sudo firewall-cmd --add-rich-rule='rule family=ipv4 source address=<apple-mac-ip>/32 port port=8080 protocol=tcp accept' --permanent
sudo firewall-cmd --add-rich-rule='rule family=ipv4 source address=<apple-mac-ip>/32 port port=5672 protocol=tcp accept' --permanent
sudo firewall-cmd --reload
```

Mac 上先创建 env，再编辑为 CentOS 地址：

```bash
./deploy/ai-worker-apple-silicon.sh env
cp deploy/.ai-worker-apple-silicon.env deploy/.ai-worker-apple-silicon.env.centos
```

编辑 `deploy/.ai-worker-apple-silicon.env.centos`：

```bash
AI_WORKER_RABBITMQ_HOST=<centos-ip-or-vpn-name>
AI_WORKER_RABBITMQ_PORT=5672
AI_WORKER_RABBITMQ_USERNAME=meeting
AI_WORKER_RABBITMQ_PASSWORD=<rabbitmq-password>
AI_WORKER_MEETING_API_BASE_URL=http://<centos-ip-or-domain>:8080
AI_WORKER_JAVA_API_BASE_URL=http://<centos-ip-or-domain>:8080
AI_WORKER_CALLBACK_HMAC_SECRET=<same-callback-secret-as-centos-java>
AI_WORKER_INTERNAL_API_HMAC_SECRET=<same-internal-secret-as-centos-java>
AI_WORKER_ADMIN_JWT_SECRET=<local-admin-secret>
```

如果 CentOS Java 通过 HTTPS 域名暴露，优先写域名：

```bash
AI_WORKER_MEETING_API_BASE_URL=https://meeting-api.example.com
AI_WORKER_JAVA_API_BASE_URL=https://meeting-api.example.com
```

CentOS Java 侧必须反向指向 Mac worker。Java 用 Docker/Compose 启动时，把
`AI_WORKER_BASE_URL` 设置为 Mac 的可达地址。这里写的是 CentOS 访问 Mac 的
地址，不是 Mac 自己看到的 `localhost`：

```bash
AI_WORKER_BASE_URL=http://<apple-mac-ip-or-vpn-name>:8090
AI_WORKER_CALLBACK_HMAC_SECRET=<same-callback-secret-as-mac-worker>
AI_WORKER_INTERNAL_API_HMAC_SECRET=<same-internal-secret-as-mac-worker>
```

如果 CentOS 无法直接访问 Mac 的 `8090`，不要用 `localhost` 凑合。先建立
VPN/Tailscale/WireGuard，或把 ai-worker 部署到 CentOS/生产 CUDA 路径。

Mac 侧也要允许 CentOS 访问 `8090`。macOS 防火墙开启时，需要允许当前终端、
Python/uv 进程或 ai-worker 进程接收入站连接。

启动 Mac worker：

```bash
set -a
. deploy/.ai-worker-apple-silicon.env.centos
set +a

./deploy/ai-worker-apple-silicon.sh run
```

验证网络和 HMAC 前，先做基础连通性检查：

```bash
# Mac -> CentOS Java
curl -fsSL http://<centos-ip-or-domain>:8080/actuator/health/readiness | jq .

# Mac -> CentOS RabbitMQ
nc -vz <centos-ip-or-vpn-name> 5672

# CentOS -> Mac ai-worker，在 CentOS 上执行
curl -fsSL http://<apple-mac-ip-or-vpn-name>:8090/internal/hardware | jq .
curl -fsSL http://<apple-mac-ip-or-vpn-name>:8090/internal/ready | jq .
```

最终在 CentOS Java 上验证聚合健康：

```bash
curl -fsSL http://<centos-ip-or-domain>:8080/actuator/health | jq '.components.aiWorker'
```

常见阻断：

| 现象 | 处理 |
|------|------|
| Mac 能访问 Java，但 Java 访问不到 Mac worker | 修 `AI_WORKER_BASE_URL`，确认 Mac 防火墙、VPN、路由和 `8090` 监听。 |
| worker 能访问 Java，但收不到任务 | 检查 `AI_WORKER_RABBITMQ_HOST`、RabbitMQ 账号、CentOS 防火墙和队列定义。 |
| HMAC rejected | 确认两边 callback/internal 两个 secret 分别一致，且两个 secret 彼此不同。 |
| CentOS Java 聚合 health 中 `aiWorker` DOWN | 从 CentOS 直接 curl Mac `/internal/health`、`/internal/ready`，再查 Java `AI_WORKER_BASE_URL`。 |
| 任一侧配置了 `localhost` | 改成对方机器可访问的 LAN/VPN/IP 或域名；跨机器部署里 `localhost` 永远只代表本机。 |

### 1.11 0-1 完成后保留的产物

| 文件 / 目录 | 是否提交 | 说明 |
|-------------|----------|------|
| `deploy/.ai-worker-apple-silicon.env` | 不提交 | 本地 HMAC/JWT 和连接信息。 |
| `deploy/.ai-worker-apple-silicon.env.centos` | 不提交 | 指向 CentOS Java/RabbitMQ 的本地连接信息。 |
| `deploy/.ai-worker-apple-silicon.env.checksums` | 不提交 | 本地 staged 权重 checksum。 |
| `$AI_WORKER_MODELS_ROOT` | 不提交 | mock 或真实模型权重目录。 |
| `apps/ai-worker/.venv` | 不提交 | uv 管理的本地虚拟环境。 |

这些产物都只属于当前机器。生产交接只需要模型版本、checksum、接口验证记录和
license 状态。

## 2. 命令矩阵

| 命令 | 用途 | 是否可离线 |
|------|------|------------|
| `./deploy/ai-worker-apple-silicon.sh stage` | 写入确定性的 mock 权重和 checksum env | 可以 |
| `HF_TOKEN=... ./deploy/ai-worker-apple-silicon.sh weights` | 下载真实 BGE 和 pyannote 权重 | 不可以 |
| `./deploy/ai-worker-apple-silicon.sh env` | 创建或复用本地连接和 HMAC env 文件 | 可以 |
| `./deploy/ai-worker-apple-silicon.sh run` | 安装 extras，并在 `:8090` 启动 `ai-worker-api` | 取决于权重和 cache |
| `./deploy/ai-worker-apple-silicon.sh verify` | 调用 `/internal/hardware` 和 `/internal/ready` | 需要 worker 正在运行 |

默认模型目录：

```bash
export AI_WORKER_MODELS_ROOT="$HOME/meeting-models"
```

如果磁盘空间在其他卷：

```bash
AI_WORKER_MODELS_ROOT=/Volumes/models/meeting-models \
  ./deploy/ai-worker-apple-silicon.sh stage
```

## 3. 架构和设备路由

设备路由是显式配置的：

| 模型 / 能力 | 设备 | DType | 原因 |
|-------------|------|-------|------|
| BGE-m3 embedding | MPS | fp32 | FlagEmbedding 可在 arm64 上运行；MPS fp16 数值风险较高。 |
| BGE-reranker-v2-m3 | MPS | fp32 | 与 BGE-m3 一致。 |
| Qwen3-ASR / funasr | CPU | fp32 | funasr operator 不是完全 MPS-clean。 |
| pyannote diarization | CPU | fp32 | MPS fallback 噪音多，且经常更慢。 |

脚本会导出：

```bash
AI_WORKER_BGE_M3_DEVICE=mps
AI_WORKER_BGE_RERANKER_DEVICE=mps
AI_WORKER_BGE_M3_DTYPE=fp32
AI_WORKER_BGE_RERANKER_DTYPE=fp32
AI_WORKER_ASR_DEVICE=cpu
AI_WORKER_DIARIZATION_DEVICE=cpu
```

除非是在验证上游 kernel 变化，不要强行把 ASR 或 diarization 改到 MPS。

## 4. Mock 权重冒烟

只想验证 worker 能启动、readiness 能通过、不想下载大模型时使用：

```bash
./deploy/ai-worker-apple-silicon.sh stage
./deploy/ai-worker-apple-silicon.sh run
```

`stage` 会写入 mock 模型目录：

```text
~/meeting-models/
  bge-m3/v1/
  bge-reranker-v2-m3/v1/
  qwen3-asr-1.7b/v2026.05.1/
  pyannote/v3.1/
```

同时写入：

```text
deploy/.ai-worker-apple-silicon.env.checksums
```

如果希望 `run` 时 checksum guard 校验这些 mock 权重，启动前 source：

```bash
set -a
. deploy/.ai-worker-apple-silicon.env.checksums
set +a
./deploy/ai-worker-apple-silicon.sh run
```

Mock 权重只能验证目录、checksum 和接口流程，不代表真实模型效果。

## 5. 真实权重路径

下载公开 BGE 权重和 gated pyannote 权重：

```bash
HF_TOKEN=hf_xxx ./deploy/ai-worker-apple-silicon.sh weights
```

下载 pyannote 前，先在 HuggingFace 接受模型条款：

```text
https://huggingface.co/pyannote/speaker-diarization-3.1
```

Qwen3-ASR 由 funasr 懒加载。演示前可提前 warm：

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

真实权重就位后启动：

```bash
./deploy/ai-worker-apple-silicon.sh run
```

离线演示：

```bash
AI_WORKER_OFFLINE=1 ./deploy/ai-worker-apple-silicon.sh run
```

只有所有必要文件已经存在本地时，才设置离线模式。否则 readiness 会因缺文件
或 import/model load 失败返回 503。

## 6. 本地环境文件

首次 `run` 或显式执行 `env` 会创建：

```text
deploy/.ai-worker-apple-silicon.env
```

文件包含本地连接和 HMAC 值：

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

脚本不会覆盖该文件。需要轮换本地 secret 时：

```bash
mv deploy/.ai-worker-apple-silicon.env deploy/.ai-worker-apple-silicon.env.old
./deploy/ai-worker-apple-silicon.sh env
```

该文件只用于本地。不要提交，不要复制到生产，不要作为 K8s Secret 来源。

## 7. 与 meeting-api 联调

支持两种本地联调形态。

### 7.1 方案 A：Compose worker，fake runtime

日常本地开发使用：

```bash
./deploy/deploy.sh local --with-observability
```

该方式启动 Compose 中的 `ai-worker:dev` 容器，使用 fake runtime，不使用
Apple Silicon 原生真实模型 worker。

### 7.2 方案 B：Mac 原生 worker，Compose meeting-api

用于 Mac 上的真实模型联调：

```bash
# 1. 先创建或复用共享 HMAC env。
./deploy/ai-worker-apple-silicon.sh env
set -a
. deploy/.ai-worker-apple-silicon.env
set +a

# 2. 只启动依赖。
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d

# 3. 启动 meeting-api，并把它指向 Mac 原生 worker。
AI_WORKER_BASE_URL=http://host.docker.internal:8090 \
  docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile full-stack up -d meeting-api

# 4. 另开一个终端启动 Mac 原生 worker。
./deploy/ai-worker-apple-silicon.sh run
```

为什么使用 `host.docker.internal`：`meeting-api` 在 Docker 容器内运行，而
原生 worker 监听 macOS host 的 `localhost:8090`。容器内访问宿主机需要使用
`host.docker.internal`。

HMAC 必须两边一致：

| 方向 | 变量 |
|------|------|
| ai-worker 回调 meeting-api | `AI_WORKER_CALLBACK_HMAC_SECRET` |
| meeting-api 调 ai-worker internal API | `AI_WORKER_INTERNAL_API_HMAC_SECRET` |

如果两个进程中的值不一致，callback、rerank 或 internal 调用会出现 HMAC
拒绝。

## 8. 验证

worker 运行后执行：

```bash
./deploy/ai-worker-apple-silicon.sh verify
```

手工验证：

```bash
curl -fsSL http://localhost:8090/internal/hardware | jq .
curl -fsSL http://localhost:8090/internal/ready | jq .
```

期望硬件形态：

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

readiness 解释：

| 状态 | 含义 |
|------|------|
| 200 | 模型目录存在，checksum guard 通过。 |
| 503 且 checksum mismatch | 期望 checksum 和文件不一致，重跑 `stage` 或更新 checksum env。 |
| 503 且 missing path | 权重目录缺失，或 `AI_WORKER_MODELS_ROOT` 错误。 |
| 503 且 import error | real-model extra 没装好，重跑 `uv sync --extra dev --extra real-models`。 |

和 Java 聚合验证：

```bash
curl -fsSL http://localhost:8080/actuator/health | jq '.components.aiWorker'
```

如果 Java 侧 `aiWorker` DOWN，优先检查 `AI_WORKER_BASE_URL` 和 HMAC。

## 9. 性能预期

本地演示的大致预期：

| 步骤 | 预期速度 | 说明 |
|------|----------|------|
| Embedding | 足够 UI demo 使用 | MPS fp32，batch size 影响明显。 |
| Rerank | 亚秒到数秒 | 候选数量决定耗时。 |
| ASR | 多数输入慢于实时 | CPU fp32，不能和 CUDA 比。 |
| Diarization | M2/M3 级别大致接近实时 | CPU-bound。 |

统一内存压力是常见失败原因。演示前关闭不必要的浏览器标签、Xcode、Docker
Desktop 重负载、IDE 索引和其他大内存任务。

## 10. 音频输入规则

ASR/diarization 冒烟前先把音频规范化：

```bash
ffmpeg -i input.mp4 \
  -ar 16000 \
  -ac 1 \
  -c:a pcm_s16le \
  sample-16k-mono.wav
```

不要把压缩 MP3/MP4 直接丢给低层 smoke 脚本。API 上传路径可能会做规范化，
但本地 debug 用 PCM WAV 更稳定。

## 11. 排障

| 现象 | 常见原因 | 处理 |
|------|----------|------|
| 脚本提示不是 Apple Silicon | 正在 Intel、Rosetta 或 Linux 上运行 | 使用 fake runtime 或 CUDA Linux 路径。 |
| `/internal/hardware` 显示 `mps=false` | x86 Python、旧 macOS 或 PyTorch 不匹配 | 切到 arm64 Python，更新 macOS/uv 环境。 |
| `uv sync` 在 `soundfile` 失败 | 缺少 native library | `brew install libsndfile pkg-config`。 |
| `sentencepiece` 构建失败 | 缺少构建工具 | `brew install cmake`。 |
| pyannote 返回 401/403 | license 未接受或 token 缺失 | 接受模型条款并设置 `HF_TOKEN`。 |
| `/internal/ready` checksum mismatch | mock checksum 搭配真实权重，或 checksum 文件过期 | 为当前文件重新生成 checksum env。 |
| Java 侧 HMAC callback 被拒绝 | worker 和 meeting-api 的 secret 不一致 | 把 `deploy/.ai-worker-apple-silicon.env` 中 HMAC 同步到 meeting-api env。 |
| meeting-api 访问不到原生 worker | 容器仍使用 `http://ai-worker:8090` | 用 `AI_WORKER_BASE_URL=http://host.docker.internal:8090` 启动 compose。 |
| ASR 输出为空或全静音 | 音频格式不合适或音量过低 | 转成 16k mono PCM WAV 后重试。 |
| 第一次推理卡顿数秒 | MPS kernel 编译 | 正常现象，测性能前先 warm 一次。 |
| OOM 或进程被 kill | 统一内存压力过高 | 停止 Docker/IDE/浏览器重负载，降低 batch size，必要时用 mock mode。 |

## 12. 清理

停止 worker：

```bash
# 在 run 终端按 Ctrl-C
```

删除生成的 env：

```bash
rm -f deploy/.ai-worker-apple-silicon.env
rm -f deploy/.ai-worker-apple-silicon.env.checksums
```

删除 mock 或真实权重：

```bash
rm -rf "${AI_WORKER_MODELS_ROOT:-$HOME/meeting-models}"
```

只有磁盘压力明显时再清 uv cache：

```bash
uv cache clean
```

## 13. 最终检查表

用于 demo 或生产 readiness handoff 前确认：

- `uname -m` 返回 `arm64`。
- `uv sync --extra dev --extra real-models` 完成。
- 模型目录存在于 `AI_WORKER_MODELS_ROOT` 下。
- HMAC 值和 meeting-api 一致。
- `/internal/hardware` 显示 BGE 在 `mps`，ASR/diarization 在 `cpu`。
- `/internal/ready` 返回 200。
- 至少一个短 PCM WAV 样本完成冒烟。
- 参与验收的人明确知道这不是生产性能形态。
- 只把模型版本、checksum、接口验证证据和 license 记录交接给生产。
- 生产部署仍使用 CUDA 镜像、GPU 节点池、生产 Secret Manager 值和
  `/opt/models` 模型卷。

相关文档：

- `deploy/DEPLOY.md` §5.5.2
- `docs/runbooks/meeting-api-java.md`
- `docs/runbooks/phase-j-acceptance.md`
- `apps/ai-worker/SPEC.md`
- `deploy/ai-worker-apple-silicon.sh`
