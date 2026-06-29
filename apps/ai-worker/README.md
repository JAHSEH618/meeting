# ai-worker

Python 3.11 + FastAPI + uv 的 GPU AI Pipeline 与 Admin BFF。详见 [`SPEC.md`](SPEC.md) 与 [`CLAUDE.md`](CLAUDE.md)。

## 本地命令

### 开发

```bash
uv sync --extra dev   # 一次性安装依赖
uv run ai-worker-api  # FastAPI 启动，监听 :8090
```

后台控制脚本已统一收敛到仓库根 `scripts/`，全部为无参脚本（委派到
`apps/ai-worker/scripts/local-control.sh` 引擎）：

```bash
# 在仓库根目录运行
./scripts/ai-worker-start.sh             # 启动 Python API / BFF (:8090)
./scripts/ai-worker-web-start.sh         # 启动 ai-worker-web (:5174/workstation/)
./scripts/ai-worker-centos-start.sh      # 联调：API 连接远端 Java
./scripts/ai-worker-web-centos-start.sh  # 联调：前端将 /api 代理到远端 Java
./scripts/ai-worker-restart.sh           # 重启 API（*-stop.sh / *-restart.sh 同理）
./scripts/ai-worker-stop.sh              # 停止 API

# 状态 / 日志仍在 apps/ai-worker 下
cd apps/ai-worker && ./status.sh && ./logs.sh
```

### 测试与验证（每个阶段完成后必跑）

```bash
# 单元测试（pytest）
uv run pytest tests/                              # 所有测试
uv run pytest tests/test_rerank.py                # 单个文件
uv run pytest tests/test_rerank.py::test_name     # 单个测试

# 类型检查（Pyright）
uv run pyright ai_worker/

# Import smoke test
uv run python -c "import ai_worker; print('OK')"
```

**CI 门禁命令：**
```bash
uv run pyright ai_worker/ && uv run pytest tests/ -x -q
```

## 架构

- **GPU Pipeline:** ASR / Diarization / Speaker / Embedding / Rerank
- **Admin BFF:** `/admin/*` - 为 ai-worker-web 提供编排接口
- **RabbitMQ Consumer:** Pika 消费任务队列
- **Callback Client:** HMAC 签名回调 Java API
