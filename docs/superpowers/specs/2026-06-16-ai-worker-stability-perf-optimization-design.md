# AI Worker 稳定性与性能优化设计

**日期**: 2026-06-16
**更新日期**: 2026-06-17
**目标环境**: 本地开发、Apple Silicon 验收、Linux/NVIDIA 容器、Kubernetes GPU 节点
**实施策略**: 在现有 Docker/K8s/Prometheus 基础上渐进增强，不重复创建已存在的健康检查和监控入口。

## 1. 当前结论

昨天的版本方向可用，但它假设 ai-worker 主要是单机开发服务，和当前仓库状态不完全一致。本次更新后的设计以当前代码为准：

- FastAPI 健康检查和指标已经在 `apps/ai-worker/ai_worker/interfaces/api/main.py` 中实现：`/internal/health`、`/internal/ready`、`/internal/hardware`、`/metrics`。
- Dockerfile 已经是多阶段构建，并通过 `BASE` 和 `UV_EXTRAS` 支持 CPU fake-mode、CUDA real-models 和能力子集。
- K8s 已经有 `StatefulSet`、非 root 运行、GPU nodeSelector/toleration、资源 requests/limits、readiness/liveness probes、Prometheus scrape annotations。
- 本地 compose 已经有 `workstation` 和 `observability` profiles，不需要新增平行的 `docker-compose.observability.yml`。
- GPU/MPS 指标、step RTF、step failure、OOM exit 计数已经在 `apps/ai-worker/ai_worker/observability/gpu_metrics.py` 中存在。

真正需要优先补齐的是：RabbitMQ 消费者恢复、任务失败分类、有界重投、回调重试策略、结构化日志上下文、任务/回调/模型加载指标、K8s 启动期保护和临时文件/磁盘边界。

## 2. 目标

1. **稳定性**：RabbitMQ/meeting-api 短暂故障后自动恢复；任务失败能按错误类型进入重试、失败回调或 DLQ；所有 broker requeue 必须有上限；进程收到 SIGTERM 时停止消费并关闭连接。
2. **性能**：显式控制模型并发、批量大小和任务超时；避免单个任务长期占住 `prefetch_count=1`；保留现有 GPU 单并发默认策略。
3. **可观测性**：所有任务、步骤、回调、模型加载、RabbitMQ 重连都有低基数字段的日志和 Prometheus 指标。
4. **容器化/运维**：沿用现有 Dockerfile 和 K8s manifest，补 `startupProbe`、`emptyDir.sizeLimit`、checksum initContainer 方案和镜像变体策略。

## 3. 非目标

- 不新增 `apps/ai-worker/ai_worker/interfaces/api/health.py`。健康检查已经在 `interfaces/api/main.py`，后续只增强现有端点。
- 不新增独立 metrics 端口 `8091`。当前 Prometheus scrape 使用 `8090/metrics`，K8s 和 compose 已围绕该入口配置。
- 不新增 `Dockerfile.optimized` 作为并行 Dockerfile。镜像优化应直接演进 `apps/ai-worker/Dockerfile`，避免维护两套路由。
- 不引入 Celery/Temporal 等新任务框架。先修复当前 RabbitMQ + Java callback 的恢复能力。
- 不在阶段一拆成多个 worker 服务。能力拆分留作阶段三之后的部署演进。

## 4. 现状分析

### 4.1 已具备能力

- `apps/ai-worker/Dockerfile`：
  - `web-build` + runtime 多阶段构建。
  - `ARG BASE` 支持 `python:3.11-slim` 和 `nvidia/cuda:*`。
  - `ARG UV_EXTRAS` 支持 `real-bge`、`real-asr`、`real-diarization`、`real-models`。
  - 默认 `HF_HUB_OFFLINE=1`、`TRANSFORMERS_OFFLINE=1`。
  - 非 root 用户 `uid=1001`。

- `infra/meeting-infra/k8s/base/ai-worker/statefulset.yaml`：
  - GPU nodeSelector/toleration。
  - `readinessProbe` 指向 `/internal/ready`。
  - `livenessProbe` 指向 `/internal/health`。
  - Prometheus scrape annotations 指向 `/metrics`。
  - 模型 PVC read-only mount，artifact emptyDir，enrollment PVC。

- `apps/ai-worker/ai_worker/interfaces/api/main.py`：
  - `/internal/health` 是 live-only，不依赖模型状态，避免模型错误导致重启风暴。
  - `/internal/ready` 汇总模型状态和 checksum guard，错误时返回 503。
  - `/internal/hardware` 暴露 torch/CUDA/MPS/package/device 诊断。
  - `/metrics` scrape 前刷新 GPU/MPS 指标。
  - `/internal/models/warmup` 支持能力子集 warmup。

- `apps/ai-worker/ai_worker/observability/gpu_metrics.py`：
  - GPU/MPS memory、GPU utilization。
  - model RTF。
  - step failures。
  - CUDA OOM exit counter。

- `apps/ai-worker/ai_worker/infrastructure/java_callback/client.py`：
  - HMAC、idempotency key、409/401 特殊处理已有。
  - 已有固定 3 次短重试，但退避是硬编码的 `0.05 * 2**attempt`，不可配置且过短。

### 4.2 主要短板

- `apps/ai-worker/ai_worker/infrastructure/mq/rabbitmq_consumer.py`：
  - `start_consuming()` 没有外层重连循环。
  - `pika.BlockingConnection` 断开后进程不会自动恢复消费。
  - `_on_message()` 对所有业务异常统一 `basic_reject(requeue=False)`，没有区分合同错误、临时依赖错误、模型暂不可用和不可恢复错误。
  - RabbitMQ definitions 已经为任务队列配置 DLX/DLQ，但 consumer 端还没有有界 requeue 策略；盲目 `requeue=True` 会带来重复投递风暴。
  - 每条消息调用 `asyncio.run()`，短期可保留，中期建议复用长期 event loop。

- `apps/ai-worker/ai_worker/common/config.py`：
  - 缺少 RabbitMQ 重连、任务超时、callback retry delays、日志格式、批量/并发上限等运维参数。

- 任务和回调可观测性：
  - 缺少任务耗时、任务结果、callback 失败/重试、RabbitMQ 重连、模型加载耗时等指标。
  - 日志仍主要依赖标准库 logging，没有统一 task/trace/tenant/step 上下文。

- K8s 启动保护：
  - real-models 冷启动可能比 liveness/readiness 初始延迟更长，应该补 `startupProbe`。
  - `artifacts` emptyDir 没有 `sizeLimit`，临时输出可能吃满节点磁盘。

## 5. 推荐路线

### 阶段一：稳定性闭环

目标：依赖短暂故障时自动恢复；不可恢复错误快速失败；所有失败有明确错误码和可观测信号。

改动：

- 在 `common/config.py` 增加稳定性配置：
  - `rabbitmq_reconnect_initial_delay_seconds`
  - `rabbitmq_reconnect_max_delay_seconds`
  - `rabbitmq_reconnect_max_attempts`
  - `rabbitmq_requeue_max_attempts`
  - `task_execution_timeout_seconds`
  - `callback_retry_delays`
  - `callback_timeout_seconds`

- 重构 `infrastructure/mq/rabbitmq_consumer.py`：
  - 抽出 `_connect_and_consume()`。
  - `start_consuming()` 外层处理 `AMQPConnectionError`、`ConnectionClosedByBroker`、`StreamLostError`。
  - 增加 `is_connected()`。
  - `_on_message()` 使用失败分类决定 ack/reject/requeue。
  - requeue 必须读取 delivery count，并受 `rabbitmq_requeue_max_attempts` 限制；超过上限时 `requeue=False`，让现有 DLX/DLQ 接管。

- 增强 `infrastructure/java_callback/client.py`：
  - 使用 `settings.callback_retry_delays`。
  - 只对网络错误、timeout、HTTP 5xx/429 重试。
  - 401、403、409、4xx 合同错误不重试。

- 增强 `interfaces/workers/rabbitmq.py`：
  - 注册 SIGTERM/SIGINT handler。
  - 调用 `consumer.stop()`，让 K8s rollout 时停止消费并关闭连接。

- 保留现有 `/internal/health` 与 `/internal/ready` 分层：
  - `/internal/health` 继续 live-only。
  - `/internal/ready` 继续依赖模型状态/checksum，不纳入 RabbitMQ 短暂状态，避免消费者断线导致 API readiness 抖动。

### 阶段二：可观测性基础

目标：5 分钟内判断失败来自 RabbitMQ、callback、模型、GPU、合同错误还是业务 pipeline。

改动：

- 新增 `common/logging_config.py`，生产输出 JSON，开发保留可读格式。
- 给 API 和 consumer 入口调用 `configure_logging()`。
- 任务处理日志统一绑定：
  - `trace_id`
  - `request_id`
  - `task_id`
  - `tenant_id`
  - `meeting_id`
  - `attempt_no`
  - `step`
  - `error_code`

- 新增或扩展指标：
  - `ai_worker_tasks_processed_total{task_type,status,error_code}`
  - `ai_worker_task_duration_seconds{task_type,status}`
  - `ai_worker_step_duration_seconds{step,status,error_code}`
  - `ai_worker_callback_requests_total{operation,status,error_code}`
  - `ai_worker_callback_retries_total{operation}`
  - `ai_worker_rabbitmq_reconnects_total{reason}`
  - `ai_worker_rabbitmq_connected`
  - `ai_worker_model_load_duration_seconds{model,status}`

- 更新现有 `infra/meeting-infra/observability/prometheus/rules.yaml`：
  - RabbitMQ reconnect surge。
  - callback failure rate。
  - model load failure。
  - task failure rate。
  - GPU memory > 90% 持续告警。

- 更新现有 Grafana dashboard：
  - `infra/meeting-infra/docker/compose/observability/grafana/dashboards/ai-worker-gpu.json`
  - `infra/meeting-infra/observability/dashboards/ai-worker-gpu.json`

### 阶段三：容器化与性能

目标：降低冷启动误杀和磁盘风险，支持按能力拆镜像/部署。

改动：

- 直接更新 `apps/ai-worker/Dockerfile`：
  - 保持现有 `BASE`/`UV_EXTRAS`。
  - 生产 real-* extras 保持 `uv sync --frozen --no-dev`，不允许 fallback resolve。
  - 可选增加 build target，但不新增平行 Dockerfile。

- 更新 `infra/meeting-infra/k8s/base/ai-worker/statefulset.yaml`：
  - 增加 `startupProbe` 指向 `/internal/health`。
  - 给 `artifacts` emptyDir 增加 `sizeLimit`。
  - 后续可增加 checksum initContainer，用相同 checksum 逻辑在服务启动前 fail fast。

- 镜像/部署变体：
  - `ai-worker:api-cpu`：fake/runtime + workstation BFF。
  - `ai-worker:embed-gpu`：`UV_EXTRAS=real-bge`。
  - `ai-worker:asr-gpu`：`UV_EXTRAS=real-asr`。
  - `ai-worker:diar-gpu`：`UV_EXTRAS=real-diarization`。
  - `ai-worker:all-gpu`：`UV_EXTRAS=real-models`。

- 性能参数化：
  - embedding/rerank 支持 batch size 配置。
  - ASR/diarization 默认单 GPU 单并发。
  - 每个 step 加 timeout 和耗时指标。

## 6. 成功标准

- RabbitMQ 重启后，consumer 在配置的退避窗口内恢复消费。
- meeting-api 短暂 5xx 或网络错误时，callback 按配置退避重试；401/409 不做无意义重试。
- checksum mismatch 时 `/internal/ready` 返回 503，`/internal/health` 仍返回 200，避免重启风暴。
- GPU OOM 仍通过 `ai_worker_oom_exits_total` 记录并退出，让平台重启。
- `/metrics` 可看到任务、步骤、回调、RabbitMQ、模型加载、GPU 维度的关键指标。
- K8s rollout/scale-down 时 consumer 能收到 SIGTERM 并停止消费。

## 7. 文档和计划关系

对应实施计划：`docs/superpowers/plans/2026-06-16-ai-worker-stage1-stability.md`。

该计划只覆盖阶段一。阶段二和阶段三应在阶段一落地并验证后拆成新的 Superpowers plan，避免一个计划跨越过多独立子系统。
