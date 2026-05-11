# ai-worker

Python 3.11+ AI 计算层。

技术栈边界：

```text
FastAPI
Clean Architecture
Celery 或 Dramatiq WorkerRuntime
Prefect 或 Temporal WorkflowEngine
LangGraph Agent
model_runtime 作为内部 package
```

Python 不直接写业务库，所有业务结果通过 Java internal callback API 回写。

