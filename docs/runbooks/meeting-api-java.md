# meeting-api (Java) — Deployment Runbook

> Standalone runbook for the Java side. Mirrors `deploy/meeting-api-java.sh`
> command-by-command and points back at the canonical DEPLOY.md
> sections — keep them in lockstep when something changes.

`apps/meeting-api` ships as a multi-module Maven build (six COLA modules:
`start / adapter / app / domain / infrastructure / client`). The Spring
Boot entry point is `meeting-api-start`. Tooling versions are pinned by
the repo-root `.tool-versions` (`java 17.0.16 / nodejs 20.18.0 / python
3.11.9`); asdf, mise, rtx, proto, and vfox all read it automatically.

## 0. Preflight

| 工具 | 最低版本 | 验证命令 |
|------|----------|---------|
| JDK 17 | `[17,18)` (Maven Enforcer 强制) | `java -version 2>&1 \| head -1` |
| Maven Wrapper | 仓库自带 `./mvnw` | `apps/meeting-api/.mvn/wrapper/maven-wrapper.properties` |
| Docker | 24+（compose / image 构建用） | `docker version --format '{{.Server.Version}}'` |
| `kubectl` / `kustomize` / `helm` | K8s 演练才需要 | DEPLOY.md §二 K8s 工具清单 |

**JDK 版本检测**：`deploy/meeting-api-java.sh` 已经封装了 macOS 上的
`/usr/libexec/java_home -v 17` 自动探测、`JAVA_HOME` major-version 校验，
未命中时会直接退出而不会触发 Maven Enforcer 在编译末期才报错的延迟失败。

## 1. 运行测试（CI 等价）

```bash
./deploy/meeting-api-java.sh test
# 等价手工命令（脚本内部展开为）：
#   cd apps/meeting-api && ./mvnw verify -q
```

包括：
- 单元测试（JUnit 5 + Mockito）
- ArchUnit 边界测试（`meeting-api-start/.../ArchitectureBoundaryTest.java`）
- Testcontainers Integration Tests（`*IT.java`，Failsafe 阶段）

需要 Docker 在线（Testcontainers 启 PG + RabbitMQ + MinIO 容器）。
Colima / OrbStack 用户见 phase-j-acceptance.md §J7 注解里的 socket override
表。

## 2. 构建 jar

```bash
./deploy/meeting-api-java.sh jar          # 仅构建
./deploy/meeting-api-java.sh jar --run    # 构建 + java -jar 直接启动
```

产物：`apps/meeting-api/meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar`。
裸跑 jar 需要外部 PostgreSQL / RabbitMQ / MinIO；最小环境变量集见
DEPLOY.md §九 `meeting-api application.yml`。

## 3. 构建 Docker 镜像

```bash
./deploy/meeting-api-java.sh image                       # tag = meeting-api:dev
./deploy/meeting-api-java.sh image meeting-api:v0.1.0    # 自定义 tag
./deploy/meeting-api-java.sh image meeting-api:dev --cross
# 等价手工命令：
#   docker build -t meeting-api:dev -f apps/meeting-api/Dockerfile apps/meeting-api/
```

Apple Silicon 跨架构发布（prod 集群是 amd64 时）：

```bash
./deploy/meeting-api-java.sh image meeting-api:v0.1.0 --cross
# 等价：docker buildx build --platform linux/amd64 ...
```

`buildx` 第一次用要 `docker buildx create --use`。生产 prod overlay 会拉
amd64 节点，arm64 镜像不能直接推上去。

镜像大小约 **1.2 GB**（基础 `eclipse-temurin:17-jre-jammy` + LibreOffice
+ jar）。LibreOffice 是为了 `EXPORT` 步骤把 docx/pdf 转格式。

## 4. Compose 启动（本地全栈）

```bash
./deploy/meeting-api-java.sh compose
# 等价：./deploy/deploy.sh local
```

启动顺序 = postgres → rabbitmq → minio → vault → meeting-api → ai-worker，
每一步都用 docker compose healthcheck 阻塞。meeting-api 的 healthcheck
走 `/actuator/health/readiness`（不是聚合 `/actuator/health`），因为
`AiWorkerHealthIndicator` 会在 ai-worker 还没启动时把聚合健康拉 DOWN。

如果要做 Phase J J1 验收，需要追加 observability：

```bash
./deploy/deploy.sh local --with-observability
# 启 Prometheus :9090 + Grafana :3000
```

## 5. Kubernetes 部署

```bash
./deploy/meeting-api-java.sh k8s dev
./deploy/meeting-api-java.sh k8s prod
# 内部就是 ./deploy/deploy.sh k8s-<env>
```

K8s 必须先把依赖装好（`./deploy/deploy.sh k8s-deps <env>`，详见
DEPLOY.md §5.3.2）。然后 deploy.sh k8s-<env> 会：

1. `kubectl create namespace meeting-<env>`（幂等）
2. 创建 `meeting-api-secret` + `ai-worker-secret`（dev 用默认值；prod
   需要 SealedSecrets / Vault）
3. `kustomize build --enable-helm` 渲染 overlay → `kubectl apply -f -`
4. `kubectl rollout status deployment/meeting-api -n meeting-<env>`
   阻塞直到就绪

### prod profile fail-fast 必读

`SPRING_PROFILES_ACTIVE=prod` 时 `ProdProfileValidator` 启动期校验下表项目，
任意一项不合规直接 Bean 阶段抛 `IllegalStateException`：

| 检查项 | 通过条件 |
|--------|---------|
| `AI_WORKER_CALLBACK_HMAC_SECRET` / `AI_WORKER_INTERNAL_API_HMAC_SECRET` | 非空、非 demo 字面量、彼此不同 |
| `AI_WORKER_BASE_URL` | 不含 `localhost` / `127.0.0.1` |
| `KMS_MASTER_KEY_ID` | ≠ `dev-kms-master-key` |
| `MEETING_KMS_MASTER_KEY_BASE64` | 显式设置（否则启动时随机生成，重启即失能） |
| `MEETING_TENANTS_ACTIVE` | 至少一个租户 ID |
| `SPRING_FLYWAY_BASELINE_ON_MIGRATE` | `false` |
| `MEETING_STORAGE_ENDPOINT` | 集群内 MinIO / S3 URL（不能用默认 `http://localhost:9000`）|
| `meeting.auth.mode` | 切到非 `in-memory` 实现 |

prod overlay (`infra/meeting-infra/k8s/overlays/prod/kustomization.yaml`)
已经把 `SPRING_PROFILES_ACTIVE=prod` / `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`
通过 ConfigMap patch 注入，剩下的密钥需要运维侧从 Vault / SealedSecrets
拉进 `meeting-api-secret`。

## 6. 数据库迁移

```bash
./deploy/meeting-api-java.sh migrate
# 打印三种迁移路径，照实际场景选一种执行
```

| 路径 | 适用 | 说明 |
|------|------|------|
| Pod 重启 | K8s 日常运维 | `kubectl rollout restart deployment/meeting-api -n meeting-dev`；meeting-api 启动时 Flyway 自动 `migrate`。 |
| Flyway Docker CLI | 本地 / 应急 | 见 §6 二维表 `docker run flyway/flyway:10 migrate`；与 meeting-api 启动时跑的是同一个 Flyway 10 baseline。 |
| psql `ON_ERROR_STOP` | debug / 验证 SQL 语法 | CI 的 `ddl-check` job 用的就是这条；任何新迁移上线前在临时 PG 上跑一遍。 |

新迁移文件命名：`apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V{yyyyMMddHHmm}__desc.sql`。不要改 `docs/ddls/`，那是 review snapshot，不是 runtime 数据源。

## 7. 故障排查

| 现象 | 排查方向 |
|------|---------|
| `mvn` 在 enforce-versions 报 `Detected JDK version` | `JAVA_HOME` 指错；先 `./deploy/meeting-api-java.sh test`（脚本会校验） |
| Testcontainers 卡在 `Could not find docker socket` | 见 phase-j-acceptance.md §J7 Colima/OrbStack/Rancher 表 |
| Pod 启动后立即 CrashLoopBackOff，日志含 `ProdProfileValidator failed` | 见 §5 prod profile 表，逐项核对 |
| Pod `CreateContainerConfigError`（ai-worker 一侧） | `ai-worker-secret` 未创建；先 `./deploy/deploy.sh k8s-dev`（脚本会创建）或手工 `kubectl create secret generic ai-worker-secret ...` |
| 聚合 `/actuator/health` 返回 DOWN，details 显示 `aiWorker` DOWN | 调 `/actuator/health/readiness` 看 meeting-api 自身就绪情况；`aiWorker` 是聚合指标，ai-worker 不在线时会拉 DOWN，是预期行为 |
| `/actuator/health` 返回 503 `outboxBacklog` DOWN | outbox publisher 落后；查 `domain_events_outbox` 行数 + `meeting-api` 日志 `outbox-publisher` 是否在轮询 |
| Flyway 迁移失败 `relation already exists` | 之前裸 SQL 跑过；启动时 baseline 关掉了。手工 `flyway repair` 或在测试库重做 |

## 8. 关联文档

- `deploy/DEPLOY.md` — 总部署文档，§5 详细 K8s、§5·5.1 Java 路径概览、§九 环境变量清单。
- `apps/meeting-api/SPEC.md` — 模块边界、ControllerAdvice 异常映射表、SSE 通道。
- `docs/runbooks/phase-j-acceptance.md` — J2 prod profile fail-fast 验收、J7 全套测试矩阵。
- `deploy/meeting-api-java.sh` — 上面所有命令的脚本入口。
