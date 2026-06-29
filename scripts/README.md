# scripts/ —— 各工程启动 / 停止 / 重启统一入口

每个工程一组固定命名、**无需任何参数**的脚本。直接 `./scripts/<name>.sh` 即可。
脚本名已固定服务与运行方式，不再像 `... start api local` 那样在命令尾部追加参数；
传入多余参数会以退出码 64 报错。

## 各工程脚本

| 工程 | 运行方式 | 启动 | 停止 | 重启 |
|---|---|---|---|---|
| **meeting-api** (Java) | Docker Compose 全栈（PostgreSQL + RabbitMQ + meeting-api） | `meeting-api-start.sh` | `meeting-api-stop.sh` | `meeting-api-restart.sh` |
| **meeting-web** (前端) | Docker（构建镜像 + nginx 容器，默认 `:5173`） | `meeting-web-start.sh` | `meeting-web-stop.sh` | `meeting-web-restart.sh` |
| **ai-worker** (Python) | 本地 uv（Python API/BFF，默认 `:8090`） | `ai-worker-start.sh` | `ai-worker-stop.sh` | `ai-worker-restart.sh` |
| **ai-worker-web** (前端) | 本地 vite dev（默认 `:5174/workstation/`） | `ai-worker-web-start.sh` | `ai-worker-web-stop.sh` | `ai-worker-web-restart.sh` |

## 一键全部

| 操作 | 脚本 | 顺序 |
|---|---|---|
| 启动全部 | `all-start.sh` | meeting-api → ai-worker → ai-worker-web → meeting-web |
| 停止全部 | `all-stop.sh` | 按启动相反顺序 |
| 重启全部 | `all-restart.sh` | all-stop → all-start |

单个工程失败不会中断其余工程，末尾汇总退出码。

## 示例

```bash
./scripts/meeting-api-start.sh        # 启动后端全栈
./scripts/ai-worker-start.sh          # 启动 Python worker
./scripts/meeting-web-restart.sh      # 重启前端
./scripts/all-stop.sh                 # 一键停止全部
```

## 运行方式说明 / 前置依赖

- **meeting-api / meeting-web** 走 Docker，需要本机 Docker 可用（`docker info` 正常）。
  - `meeting-api-*` 使用 `infra/meeting-infra/docker/compose/docker-compose.yml`
    + `docker-compose.prod.yml`，并按需加载 `deploy/.meeting-api-prod.env`
    或回退到 `deploy/.meeting-api-oss.env`（缺省则用 compose 默认值）。
  - `meeting-web-*` 默认挂到 meeting-api 的 compose 网络 `compose_default` 上以反代 `/api`；
    可用 `MEETING_WEB_PORT` / `MEETING_WEB_NETWORK` / `MEETING_WEB_IMAGE` 覆盖默认值。
- **ai-worker / ai-worker-web** 走本地开发：ai-worker 需要 `uv`，ai-worker-web 需要
  已安装依赖（`cd apps/ai-worker-web && npm ci`）。这两组脚本是
  `apps/ai-worker/scripts/local-control.sh` 的薄封装，复用同一套久经测试的逻辑。

## 状态 / 日志

启停脚本仅负责 start/stop/restart。查看状态与日志：

- meeting-api / meeting-web：`docker logs -f meeting-api`、`docker logs -f meeting-web`，
  或仓库根目录的 `./status.sh` / `./logs.sh`。
- ai-worker / ai-worker-web：`apps/ai-worker/status.sh`、`apps/ai-worker/logs.sh`。

## 与 `apps/ai-worker/` 下脚本的关系

`apps/ai-worker/` 下仍保留 `api-*.sh` / `web-*.sh` / `all-*.sh` 及其 `*-centos-*`
联调变体——它们由单元测试 `tests/test_local_control_scripts.py` 固定校验、并被
`docs/runbooks/ai-worker-apple-silicon.md` 引用（含两机 CentOS 联调流程），故予以保留。
本目录的 `ai-worker-*` / `ai-worker-web-*` 与它们调用同一个 `local-control.sh` 引擎，
只是把 4 个工程的本地入口统一收敛到仓库根 `scripts/` 下。
