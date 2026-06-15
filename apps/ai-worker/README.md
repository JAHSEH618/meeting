# ai-worker

Python 3.11+ AI 计算层。

详细工程规格见 [`SPEC.md`](SPEC.md)。

技术栈边界：

```text
FastAPI
Clean Architecture
Pika RabbitMQ Consumer (direct implementation, Dramatiq/Prefect deferred)
LangGraph Agent
model_runtime 作为内部 package
```

Python 不直接写业务库，所有业务结果通过 Java internal callback API 回写。

## 本地命令

```bash
uv sync --extra dev
uv run --extra dev pytest
uv run ai-worker-api
```

默认端口：`8090`。

### 连接远程 Java 服务的工作站前端

当 meeting-api 已运行在 `10.9.50.179:8080` 时，Python 端 BFF 这样启动：

```bash
AI_WORKER_JAVA_API_BASE_URL=http://10.9.50.179:8080 \
AI_WORKER_MEETING_API_BASE_URL=http://10.9.50.179:8080 \
uv run ai-worker-api
```

默认会使用 Python 专用前端自己的 `/workstation/login` 登录页，并向
Java `/api/auth/login` 提交账号密码。若远程 Java 另有完整网页登录流程
且登录后会带 `#access_token=...` 跳回工作站，再设置
`AI_WORKER_AUTH_LOGIN_URL` 指向该登录页；不要指向 `/api/auth/login`，
它是 JSON POST API。`AI_WORKER_ADMIN_JWT_SECRET` / audience / issuer
必须和 Java 侧保持一致。

专用前端在 `apps/ai-worker-web` 启动：

```bash
npm run dev
```

访问 `http://localhost:5174/workstation/`。如需换 Java 地址，可设置
`VITE_MEETING_API_TARGET=http://host:port npm run dev` 覆盖前端 `/api` 代理。

如果要让 Python 端自己托管已构建的前端，而不是单独跑 Vite：

```bash
cd ../ai-worker-web
npm run build

cd ../ai-worker
AI_WORKER_JAVA_API_BASE_URL=http://10.9.50.179:8080 \
AI_WORKER_MEETING_API_BASE_URL=http://10.9.50.179:8080 \
AI_WORKER_ADMIN_UI_DIST_PATH=../ai-worker-web/dist \
uv run ai-worker-api
```

这时访问 `http://localhost:8090/` 会跳转到 `http://localhost:8090/workstation/`。

已初始化的最小接口：

```text
GET /internal/health
GET /internal/models
GET /internal/workflows/{task_id}
```

当前只提供 FastAPI、配置和 Java callback client 骨架；具体 ASR、Diarization、speaker embedding、workflow runtime 后续在对应 package 中实现。
