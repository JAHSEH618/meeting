# ai-worker

Python 3.11+ AI 计算层。

详细工程规格见 [`SPEC.md`](SPEC.md)。

技术栈边界：

```text
FastAPI
Clean Architecture
Dramatiq WorkerRuntime
Prefect WorkflowEngine
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

已初始化的最小接口：

```text
GET /internal/health
GET /internal/models
GET /internal/workflows/{task_id}
```

当前只提供 FastAPI、配置和 Java callback client 骨架；具体 ASR、Diarization、speaker embedding、workflow runtime 后续在对应 package 中实现。
