# meeting-api Java 部署运行手册

本文档是 `meeting-api` Java 服务的生产标准部署手册。本文档核心针对**两机部署拓扑（CentOS/ECS Java + 远端 AI Worker）**、**阿里云 OSS 生产级集成配置**以及**后台控制脚本**进行全新编写，提供超详细的一步一步部署流程。

---

## 0. 部署决策与两机拓扑

### 0.1 生产级决策
- **摒弃 MinIO 本地存储**：生产及演练环境下，Java 服务的对象存储统一强制使用**阿里云 OSS**（`STORAGE_TYPE=oss`）。
- **两机分离部署**：禁止将 Java API 服务与真实模型 AI 推理服务部署在同一台机器上，以防高负载的 GPU/CPU 模型计算拖垮 Java 核心业务的响应。
- **推荐运行模式**：使用 **Docker Compose 容器栈模式**（生产级标准形态）或 **Standalone JAR 守护进程模式** 部署在 CentOS 机器上。

### 0.2 两机部署标准拓扑架构

两机部署要求将 Java 核心服务与 AI 权重推理服务彻底分离。两者通过内网/VPN 网络及一致的 HMAC 密钥进行双向通信。

```mermaid
graph TD
    subgraph Java 机器 (CentOS/ECS)
        java[meeting-api Java 17]
        pg[PostgreSQL + pgvector]
        mq[RabbitMQ Queue]
        control[meeting-api-control.sh]
    end

    subgraph AI Worker 机器 (Apple Silicon Mac 或 GPU 节点)
        worker[ai-worker-api]
    end

    subgraph 云资源 (阿里云)
        oss[阿里云 OSS Bucket]
    end

    %% Flow lines
    java -->|1. 写入音频/导出物| oss
    java -->|2. 发送 AI 任务/HMAC| mq
    worker -->|3. 监听队列/拉取任务| mq
    worker -->|4. 只读凭据拉取音频| oss
    worker -->|5. 推理完毕回调/HMAC| java

    style java fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    style worker fill:#efebe9,stroke:#5d4037,stroke-width:2px
    style oss fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px
```

两机联调必须配置双方均能访问的内网 IP、VPN IP 或域名。**禁止在跨机器配置中使用 `localhost` 或 `127.0.0.1`**。

| 方向 | 正确配置 | 禁止值 |
|------|----------|--------|
| **Java -> Mac worker** | Java 侧 `AI_WORKER_BASE_URL=http://<apple-mac-ip-or-vpn-name>:8090` | `localhost`, `127.0.0.1`, `host.docker.internal` |
| **Mac worker -> Java** | Mac 侧 `AI_WORKER_MEETING_API_BASE_URL=http://<centos-ip-or-domain>:8080` | `localhost`, `127.0.0.1` |
| **Mac worker -> RabbitMQ** | Mac 侧 `AI_WORKER_RABBITMQ_HOST=<centos-ip-or-vpn-name>` | 对公网开放的 RabbitMQ 物理端口 |
| **Mac worker -> OSS** | Mac 侧 `AI_WORKER_STORAGE_BACKEND=oss` + 只读 RAM 凭据 | 复用 Java 侧写权限的主账号/RAM 凭据 |

---

## 1. 阿里云 OSS 生产级云资源准备

在进行 Java 部署前，必须在阿里云控制台完成 Bucket 的初始化和最小化权限（RAM 策略）的划分。

### 1.1 创建 OSS Bucket
在阿里云 OSS 控制台创建以下三个**私有** Bucket，并建议选择与 Java 机器（CentOS ECS）相同的地域（例如 `华东1-杭州`）：

1. **音频 Bucket**：`meeting-audio-auska` — 存储用户上传的原始及经过规范化的音频。
2. **中间产物 Bucket**：`meeting-artifacts` — 存储 AI 推理产生的各种切片及临时推理缓存。
3. **导出文件 Bucket**：`meeting-exports` — 存储由 Java 服务生成的 PDF/DOCX 导出文档。

---

### 1.2 创建 RAM 用户与最小化 Policy 策略

> [!CAUTION]
> 绝对不要在生产环境使用阿里云主账号（Root AccessKey）。必须为 Java 应用和远程 Worker 分别创建独立的 RAM 子账号，并授予最小必要权限。

#### 角色 A：Java 服务 RAM 子用户 (`meeting-java-backend`)
Java 服务负责上传音频、写入中间件及删除过期导出物。需要对三个 Bucket 拥有**读、写、删除**权限。

创建自定义权限策略 `AliyunOSSMeetingJavaPolicy` 并绑定至该子用户：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:PutObject",
        "oss:GetObject",
        "oss:HeadObject",
        "oss:DeleteObject",
        "oss:ListObjects"
      ],
      "Resource": [
        "acs:oss:*:*:meeting-audio-auska",
        "acs:oss:*:*:meeting-audio-auska/*",
        "acs:oss:*:*:meeting-artifacts",
        "acs:oss:*:*:meeting-artifacts/*",
        "acs:oss:*:*:meeting-exports",
        "acs:oss:*:*:meeting-exports/*"
      ]
    }
  ]
}
```

#### 角色 B：AI Worker 推理子用户 (`meeting-ai-worker`)
远程推理 Worker 仅负责读取音频数据以执行 ASR 和说话人分离，**严禁其拥有写入或删除权限**。

创建自定义权限策略 `AliyunOSSMeetingWorkerReadOnlyPolicy` 并绑定至该子用户：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:GetObject",
        "oss:HeadObject"
      ],
      "Resource": [
        "acs:oss:*:*:meeting-audio-auska/*",
        "acs:oss:*:*:meeting-artifacts/*",
        "acs:oss:*:*:meeting-exports/*"
      ]
    }
  ]
}
```

---

### 1.3 配置 CORS (跨域资源共享) 规则
由于前端浏览器会请求 Java 服务生成的**预签名安全 URL** 直接将音频上传到 OSS Bucket，必须为 **`meeting-audio-auska`** Bucket 配置 CORS：

1. 登录阿里云 OSS 控制台，进入 `meeting-audio-auska` 详情页。
2. 点击 **数据安全 -> 跨域规则 (CORS) -> 创建规则**。
3. 按如下规则填写：
   - **来源 (AllowedOrigin)**：`https://meeting.example.com`（您的生产前端域名）及开发测试域名 `http://localhost:3000`。
   - **允许 Method**：`GET`, `PUT`, `HEAD`
   - **允许 Headers**：`*`
   - **暴露 Headers**：`ETag`, `x-oss-request-id`
   - **缓存时间**：`3600` 秒。

---

## 2. 一步一步超详细部署步骤 (Java 侧)

下面以全新 CentOS 9 / ECS 机器为例，详述从零开始的全部命令行配置。

### 第一步：系统更新与 JDK 17 安装

```bash
# 1. 更新系统软件包仓库
sudo dnf update -y

# 2. 安装 JDK 17 (Maven Enforcer 严格限制在 [17,18) 版本，Java 21/25 将无法通过编译)
sudo dnf install -y java-17-openjdk java-17-openjdk-devel git curl jq unzip tar openssl

# 3. 验证 Java 版本
java -version
```

---

### 第二步：安装 Docker 引擎与 Docker Compose Plugin

```bash
# 1. 添加入口源并安装 Docker CE
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 2. 启动并开机自启 Docker 服务
sudo systemctl enable --now docker

# 3. 将当前用户加入 docker 用户组 (退出终端重新登录后生效)
sudo usermod -aG docker "$USER"
```

---

### 第三步：拉取源码与编译验证

```bash
# 1. 拉取仓库代码
git clone https://github.com/JAHSEH618/meeting.git
cd meeting

# 2. 执行编译集成测试门禁 (Testcontainers 会在后台拉取临时容器进行模拟验证)
./deploy/meeting-api-java.sh test
```

---

### 第四步：配置服务器网络与安全组防火墙

Java 机器作为核心计算集群，需要在外网或 VPN 内网中暴露必要的业务与管理端口。同时需要限制远程 Worker 对队列的访问。

```bash
# 1. 启动 firewalld 防火墙
sudo systemctl enable --now firewalld

# 2. 仅对特定 Worker Mac 暴露 PostgreSQL、RabbitMQ 以及 Java REST 服务
export WORKER_IP="<apple-mac-ip-or-vpn-ip>"

# 允许外部调用 Java 服务的 8080 端口
sudo firewall-cmd --add-rich-rule="rule family=ipv4 source address=${WORKER_IP}/32 port port=8080 protocol=tcp accept" --permanent

# 允许远程 Worker 访问 RabbitMQ 的 5672 端口进行队列监听
sudo firewall-cmd --add-rich-rule="rule family=ipv4 source address=${WORKER_IP}/32 port port=5672 protocol=tcp accept" --permanent

# 重新加载防火墙策略
sudo firewall-cmd --reload
```

---

### 第五步：生成生产环境变量文件 (.meeting-api-prod.env)

在 CentOS 的 `deploy` 目录下创建一个不可提交的生产环境配置文件，只允许部署用户读写：

```bash
# 1. 生成安全 HMAC 签名密钥 (绝对不要用 Demo 默认值，且两组密钥互不相同)
CALLBACK_SECRET="$(openssl rand -hex 32)"
INTERNAL_SECRET="$(openssl rand -hex 32)"
KMS_MASTER_BASE64="$(openssl rand -base64 32)"

# 2. 写入私有环境文件
cat > deploy/.meeting-api-prod.env <<EOF
# ==============================================================================
# Java Backend 生产环境配置
# ==============================================================================
SPRING_PROFILES_ACTIVE=prod
SPRING_FLYWAY_BASELINE_ON_MIGRATE=false

# 1. 两机通信路由定义
JAVA_HOST=$(curl -s ipinfo.io/ip)  # 本机外网/内网可达 IP
WORKER_HOST=<apple-mac-ip-or-vpn-ip>  # 远程 Worker 机器 IP
AI_WORKER_BASE_URL=http://<apple-mac-ip-or-vpn-ip>:8090

# 2. 数据库连接 (指向上一步启动的本地容器或托管云数据库)
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5432
POSTGRES_DB=meeting
POSTGRES_USER=meeting
POSTGRES_PASSWORD=<自定义超强密码-postgres_password>

# 3. 队列连接
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5672
RABBITMQ_USER=meeting
RABBITMQ_PASS=<自定义超强密码-rabbitmq_password>

# 4. 阿里云 OSS 生产参数 (Java 专属读写 AK/SK)
STORAGE_TYPE=oss
STORAGE_BUCKET_AUDIO=meeting-audio-auska
STORAGE_BUCKET_ARTIFACTS=meeting-artifacts
STORAGE_BUCKET_EXPORTS=meeting-exports
OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
OSS_REGION=cn-hangzhou
OSS_ACCESS_KEY_ID=<Java Backend 专用读写子账号 AK>
OSS_ACCESS_KEY_SECRET=<Java Backend 专用读写子账号 SK>

# 5. 双向通信 HMAC 密钥
AI_WORKER_CALLBACK_HMAC_SECRET=${CALLBACK_SECRET}
AI_WORKER_INTERNAL_API_HMAC_SECRET=${INTERNAL_SECRET}

# 6. 数据及加密服务
KMS_MASTER_KEY_ID=prod-kms-master-key
MEETING_KMS_MASTER_KEY_BASE64=${KMS_MASTER_BASE64}
MEETING_TENANTS_ACTIVE=tenant-default
EOF

# 3. 严格限制权限
chmod 600 deploy/.meeting-api-prod.env
```

---

### 第六步：启动中间件基础设施依赖

启动 CentOS 机器本地的 PostgreSQL 及 RabbitMQ 容器：

```bash
# 以后台静默方式单独启动数据库与队列组件
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d postgres rabbitmq
```

---

## 3. 后台控制脚本 (`meeting-api-control.sh`)

为了方便快速启动、停止和重启服务，不再占用前台终端，我们编写了高度可靠的后台控制脚本 `deploy/meeting-api-control.sh`。它完美支持 **Docker Compose (推荐)** 与 **JAR** 两种后台模式。

### 3.1 控制脚本命令矩阵

| 操作 | 容器运行模式 (Compose) | 物理进程模式 (JAR) | 场景说明 |
|------|-------------------|-----------------|----------|
| **启动服务** | `./deploy/meeting-api-control.sh start compose` | `./deploy/meeting-api-control.sh start jar` | 自动构建/加载最新代码包并在后台运行 |
| **停止服务** | `./deploy/meeting-api-control.sh stop compose` | `./deploy/meeting-api-control.sh stop jar` | 优雅停止服务，清理 PID/临时容器 |
| **重启服务** | `./deploy/meeting-api-control.sh restart compose`| `./deploy/meeting-api-control.sh restart jar`| 平滑重建或拉起，加载最新环境变量 |
| **查看状态** | `./deploy/meeting-api-control.sh status compose` | `./deploy/meeting-api-control.sh status jar` | 验证 Actuator `/health` 与端口占用状态 |
| **查看日志** | `./deploy/meeting-api-control.sh logs compose` | `./deploy/meeting-api-control.sh logs jar` | 实时追踪后台 Spring Boot 日志输出流 |

---

### 3.2 容器堆栈模式启动 (标准生产演练路径)

使用 Docker 容器化启动是生产验证的标准形态。此脚本会读取 `deploy/.meeting-api-prod.env` 并注入容器运行。

```bash
# 1. 启动容器核心服务
./deploy/meeting-api-control.sh start compose

# 2. 检查聚合健康指标 (等待一会直到返回 READY)
./deploy/meeting-api-control.sh status compose

# 3. 追踪日志流以监听外部 AI Worker 的联调握手
./deploy/meeting-api-control.sh logs compose
```

---

### 3.3 standalone JAR 物理进程模式启动

如果您不希望在容器内运行，可直接通过 `java -jar` 的物理进程方式，在 CentOS 本地作为后台守护进程启动。

```bash
# 1. 编译并打包 Java 项目，同时在后台以 nohup 方式运行 jar
./deploy/meeting-api-control.sh start jar

# 2. 查看后台进程 PID 与端口占用
./deploy/meeting-api-control.sh status jar

# 3. 追踪物理后台输出流
./deploy/meeting-api-control.sh logs jar
```

JAR 运行日志保存在 `deploy/meeting-api.log`，物理 PID 保存在 `deploy/meeting-api.pid`。

---

## 4. 跨机器联调与聚合状态验证

当 CentOS 侧的 Java 服务启动就绪后，按以下步骤与 Mac/远程 Worker 建立通信。

### 4.1 在 CentOS 生成并传递环境参数给 Worker
我们通过本地的 `.meeting-api-prod.env` 自动生成远程 Mac Worker 专属的配置副本，然后通过安全渠道（如 SCP）发送给 Mac 设备：

```bash
# 生成专为 Worker Mac 设置的环境配置文件副本
cat > deploy/.ai-worker-apple-silicon.env.centos <<EOF
AI_WORKER_RABBITMQ_HOST=${JAVA_HOST}
AI_WORKER_RABBITMQ_PORT=5672
AI_WORKER_RABBITMQ_USERNAME=meeting
AI_WORKER_RABBITMQ_PASSWORD=<CentOS 侧生成的 rabbitmq_password>
AI_WORKER_MEETING_API_BASE_URL=http://${JAVA_HOST}:8080
AI_WORKER_JAVA_API_BASE_URL=http://${JAVA_HOST}:8080
AI_WORKER_CALLBACK_HMAC_SECRET=${CALLBACK_SECRET}
AI_WORKER_INTERNAL_API_HMAC_SECRET=${INTERNAL_SECRET}
AI_WORKER_ADMIN_JWT_SECRET=$(openssl rand -hex 32)

# OSS 凭据必须填入上面创建的 Worker 专用 OSS 只读 RAM AK/SK
AI_WORKER_STORAGE_BACKEND=oss
AI_WORKER_OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
AI_WORKER_OSS_REGION=cn-hangzhou
AI_WORKER_OSS_ACCESS_KEY_ID=<远程 Worker 只读账号 AK>
AI_WORKER_OSS_ACCESS_KEY_SECRET=<远程 Worker 只读账号 SK>
EOF

# 发送给远程 Mac 机器 (假设 Mac 开启了 SSH 服务且用户为 user)
scp deploy/.ai-worker-apple-silicon.env.centos user@<apple-mac-ip-or-vpn-ip>:/Users/user/meeting/deploy/
```

在 Mac 侧接收到该配置后，重命名并启动服务（详见 `ai-worker-apple-silicon.md`）：
```bash
# (在 Mac 设备上运行)
mv deploy/.ai-worker-apple-silicon.env.centos deploy/.ai-worker-apple-silicon.env
./deploy/ai-worker-control.sh start
```

---

### 4.2 双向连通性验证

当两端均已启动后台服务后，在 CentOS 服务器侧验证远程 Worker 连接：

```bash
# 1. 验证 CentOS 能直接穿透并获取 Mac 侧 AI 硬件状态
curl -fsSL http://<apple-mac-ip-or-vpn-ip>:8090/internal/hardware | jq .

# 2. 验证 CentOS 能直连 Mac Worker 且状态为 READY
curl -fsSL http://<apple-mac-ip-or-vpn-ip>:8090/internal/ready | jq .

# 3. 验证 Java API 的 Actuator 健康检查中 aiWorker 节点指标变为 UP
curl -fsSL http://localhost:8080/actuator/health | jq '.components.aiWorker'
```

---

## 5. 运维排障

在进行生产级演练时，若遇到调用不通，请对照以下矩阵进行自我排查：

| 现象 | 原因分析 | 处理策略 |
|------|----------|----------|
| **Java 启动门禁被阻断，报错：ProdProfileValidator failed** | 在开启 `SPRING_PROFILES_ACTIVE=prod` 后，出于安全要求，系统会阻断带有 Demo 默认值或 localhost 路由的配置 | 1. 确保配置项中没有 `localhost`、`127.0.0.1` 填充在外部地址。<br>2. 确保 `KMS_MASTER_KEY_ID` 不是 `dev-kms-master-key`，且 `AI_WORKER_*_HMAC_SECRET` 两组互不相同。 |
| **Java 聚合健康度中 `aiWorker` 显示 DOWN** | 1. 物理防火墙拦截了 `8090` 端口。<br>2. Mac 端的 AI Worker 进程挂掉或根本未启动。 | 1. 在 CentOS 上运行 `telnet <mac-ip> 8090`，排查路由器/物理 VPN 拦截。<br>2. 去 Mac 终端运行 `./deploy/ai-worker-control.sh status` 确认 Python 推理进程在线。 |
| **Outbox 队列消费堵塞，后台日志出现大量的 HMAC 拒签** | Java 的密钥与 Worker 密钥发生了漂移或人工配置错乱 | 检查 CentOS 的 `.meeting-api-prod.env` 与 Mac 的 `.ai-worker-apple-silicon.env`。检查 `AI_WORKER_CALLBACK_HMAC_SECRET` 和 `AI_WORKER_INTERNAL_API_HMAC_SECRET` 四个密钥，两两严格一致。 |
| **OSS 音频操作失败，提示 403 / SignatureDoesNotMatch** | 阿里云 OSS endpoint 配置或 AK/SK 有误，导致服务端签名失败 | 1. 确认 `STORAGE_BUCKET_*` 填写的名称在阿里云控制台确实存在且拼写正确。<br>2. 检查 `OSS_ENDPOINT` 在公网下是否填成了内网 `-internal` 地址。对于两机不在同地域 VPC 时，必须统一使用公网 Endpoint 进行上传测试。 |

---

## 6. 最终检查清单

用于上线或验收前确保完全合规：

- [ ] `java -version` 确认主版本号严格为 `17`。
- [ ] 阿里云上配置了三个**私有** Bucket，并分别为 Java (RWD) 和 Worker (RO) 赋予了最小权限 RAM 策略。
- [ ] 音频 Bucket (`meeting-audio-auska`) 已经手动配置了针对前端域名的跨域 CORS 规则。
- [ ] Java 端的控制脚本 `deploy/meeting-api-control.sh` 能够在后台拉起容器并创建 PID。
- [ ] 安全防火墙策略已被添加，拒绝任何不合规的外部连接，特别是对 RabbitMQ 5672 端口的拦截。
- [ ] 聚合 `/actuator/health` 对外显示 `status: UP`，确认 `postgresRls`、`rabbitMqQueue`、`aiWorker` 全部就绪。
- [ ] 完成一次基于公网 OSS 预签名 URL 的完整音频上传与 ASR / 说话人分离联调全链路冒烟测试。

相关参考文档：
- `docs/runbooks/ai-worker-apple-silicon.md`
- `deploy/DEPLOY.md`
- `deploy/meeting-api-control.sh`
