# AI Worker 稳定性与性能优化设计

**日期**: 2026-06-16  
**作者**: AI Assistant  
**目标环境**: 单机开发环境  
**实施策略**: 渐进式三阶段优化

## 1. 概览

### 1.1 优化目标

针对 **ai-worker 单机开发环境**的全面优化，提升：

1. **稳定性**：防止崩溃、自动恢复、资源清理
2. **可观测性**：结构化日志、丰富指标、易于调试
3. **开发体验**：快速启动、容器化、配置简化

### 1.2 三阶段路线图

```
阶段一：核心稳定性（1-2天）
├─ 错误处理增强
├─ 资源清理机制
└─ 健康检查改进

阶段二：可观测性基础（1-2天）
├─ 结构化日志（structlog）
├─ Prometheus 指标扩展
└─ 本地监控面板

阶段三：容器化优化（1天）
├─ 多阶段构建优化
├─ docker-compose 增强
└─ 配置管理改进
```

### 1.3 现状分析

**已有基础：**
- ✅ Dockerfile 多阶段构建
- ✅ 基础 Prometheus 指标（`gpu_metrics.py`）
- ✅ 健康检查端点
- ✅ RabbitMQ 消费者基础重试

**待改进：**
- ❌ 模型加载失败无重试
- ❌ 日志是 Python 标准库（难搜索、无结构）
- ❌ GPU OOM 后无清理机制
- ❌ RabbitMQ 连接断开无自动重连
- ❌ 容器镜像体积大（~8GB）
- ❌ 本地开发需手动启动多个服务

---

## 2. 阶段一：核心稳定性

### 2.1 错误处理增强

#### 2.1.1 模型加载重试装饰器

**文件**: `ai_worker/common/retry.py`

```python
import time
import logging
from functools import wraps
from typing import Callable, Type, Tuple

logger = logging.getLogger(__name__)

def exponential_backoff(attempt: int, base: float = 2.0, max_delay: float = 60.0) -> float:
    """计算指数退避延迟"""
    delay = min(base ** attempt, max_delay)
    return delay

def retry(
    max_attempts: int = 3,
    backoff_base: float = 2.0,
    max_delay: float = 60.0,
    on_exception: Tuple[Type[Exception], ...] = (Exception,),
):
    """重试装饰器，支持指数退避"""
    def decorator(func: Callable) -> Callable:
        @wraps(func)
        def wrapper(*args, **kwargs):
            for attempt in range(max_attempts):
                try:
                    return func(*args, **kwargs)
                except on_exception as exc:
                    if attempt == max_attempts - 1:
                        logger.error(
                            f"{func.__name__} failed after {max_attempts} attempts",
                            exc_info=True
                        )
                        raise
                    delay = exponential_backoff(attempt, backoff_base, max_delay)
                    logger.warning(
                        f"{func.__name__} failed (attempt {attempt + 1}/{max_attempts}), "
                        f"retrying in {delay:.1f}s: {exc}"
                    )
                    time.sleep(delay)
            return None
        return wrapper
    return decorator
```

**应用位置**:
- `ai_worker/model_runtime/bge_m3.py` - 模型加载
- `ai_worker/model_runtime/qwen3_asr.py` - ASR 模型加载
- `ai_worker/model_runtime/pyannote_diarization.py` - Diarization 模型加载

#### 2.1.2 RabbitMQ 消费者断线重连

**文件**: `ai_worker/infrastructure/mq/rabbitmq_consumer.py`

**改动**:
```python
class RabbitMqTaskConsumer:
    def start_consuming(self) -> None:
        """启动消费者，自动处理连接断开重连"""
        reconnect_delay = settings.rabbitmq_reconnect_delay
        
        while True:  # 外层循环处理连接断开
            try:
                self._connect_and_consume()
            except (AMQPConnectionError, ConnectionClosedByBroker) as exc:
                logger.warning(
                    f"RabbitMQ 连接断开: {exc}，{reconnect_delay}秒后重试"
                )
                time.sleep(reconnect_delay)
            except KeyboardInterrupt:
                logger.info("收到中断信号，停止消费者")
                break
    
    def _connect_and_consume(self) -> None:
        """建立连接并开始消费（原有的 start_consuming 逻辑）"""
        credentials = pika.PlainCredentials(self.config.username, self.config.password)
        parameters = pika.ConnectionParameters(
            host=self.config.host,
            port=self.config.port,
            virtual_host=self.config.virtual_host,
            credentials=credentials,
            heartbeat=30,
            blocked_connection_timeout=30,
        )
        self._connection = pika.BlockingConnection(parameters)
        channel = self._connection.channel()
        self._channel = channel
        channel.basic_qos(prefetch_count=1)
        
        for queue in self.config.queues:
            channel.basic_consume(
                queue=queue,
                on_message_callback=self._on_message,
                auto_ack=False,
            )
        
        logger.info(f"RabbitMQ 消费者已连接，监听队列: {self.config.queues}")
        channel.start_consuming()
```

#### 2.1.3 回调重试增强

**文件**: `ai_worker/infrastructure/callback/client.py`

**改动**:
```python
from ai_worker.common.config import settings

class CallbackClient:
    def __init__(self):
        # 解析重试延迟配置（逗号分隔的秒数）
        self.retry_delays = [
            int(x.strip()) 
            for x in settings.callback_retry_delays.split(",")
        ]
    
    async def send_callback(self, url: str, payload: dict) -> None:
        """发送回调，带指数退避重试"""
        last_error = None
        
        for attempt, delay in enumerate(self.retry_delays):
            try:
                response = await self.http_client.post(url, json=payload)
                response.raise_for_status()
                logger.info(f"回调成功: {url}")
                return
            except httpx.HTTPError as exc:
                last_error = exc
                if attempt < len(self.retry_delays) - 1:
                    logger.warning(
                        f"回调失败 (尝试 {attempt + 1}/{len(self.retry_delays)}): {exc}，"
                        f"{delay}秒后重试"
                    )
                    await asyncio.sleep(delay)
        
        # 所有重试都失败
        logger.error(f"回调最终失败 {url}: {last_error}")
        raise last_error
```

### 2.2 资源清理机制

#### 2.2.1 GPU 内存清理上下文

**文件**: `ai_worker/common/gpu_context.py`

```python
import logging
from contextlib import contextmanager
from typing import Iterator

logger = logging.getLogger(__name__)

@contextmanager
def gpu_context(device: str = "cuda") -> Iterator[None]:
    """GPU 资源管理上下文，自动清理显存"""
    try:
        yield
    finally:
        try:
            import torch
            if device.startswith("cuda") and torch.cuda.is_available():
                torch.cuda.empty_cache()
                torch.cuda.synchronize()
                logger.debug("GPU 显存已清理")
            elif device == "mps" and hasattr(torch.backends, "mps"):
                if torch.backends.mps.is_available():
                    # MPS 清理（如果支持）
                    torch.mps.empty_cache() if hasattr(torch.mps, "empty_cache") else None
        except ImportError:
            pass  # torch 未安装，跳过清理
        except Exception as exc:
            logger.warning(f"GPU 清理失败: {exc}")
```

**应用示例**:
```python
# 在 ASR/Diarization 推理中使用
with gpu_context(settings.asr_device):
    result = asr_model.transcribe(audio)
```

#### 2.2.2 临时文件自动清理

**文件**: `ai_worker/common/tempfile_manager.py`

```python
import logging
import time
from pathlib import Path
from datetime import datetime, timedelta

from ai_worker.common.config import settings

logger = logging.getLogger(__name__)

class TempFileManager:
    """临时文件管理器，定期清理过期文件"""
    
    def __init__(
        self,
        max_age_hours: int = None,
        cleanup_interval_minutes: int = None,
    ):
        self.max_age_hours = max_age_hours or settings.temp_file_max_age_hours
        self.cleanup_interval = cleanup_interval_minutes or settings.temp_file_cleanup_interval_minutes
        self.temp_dir = Path(settings.artifact_store_root) / "temp"
        self.temp_dir.mkdir(parents=True, exist_ok=True)
    
    def cleanup_old_files(self) -> int:
        """清理超过 max_age_hours 的临时文件，返回删除数量"""
        cutoff_time = datetime.now() - timedelta(hours=self.max_age_hours)
        deleted_count = 0
        
        for file_path in self.temp_dir.rglob("*"):
            if not file_path.is_file():
                continue
            
            try:
                mtime = datetime.fromtimestamp(file_path.stat().st_mtime)
                if mtime < cutoff_time:
                    file_path.unlink()
                    deleted_count += 1
            except Exception as exc:
                logger.warning(f"删除临时文件失败 {file_path}: {exc}")
        
        if deleted_count > 0:
            logger.info(f"清理了 {deleted_count} 个过期临时文件")
        
        return deleted_count
    
    def start_background_cleanup(self):
        """启动后台清理任务（在主线程或异步任务中调用）"""
        import threading
        
        def cleanup_loop():
            while True:
                try:
                    self.cleanup_old_files()
                except Exception as exc:
                    logger.error(f"后台清理任务失败: {exc}", exc_info=True)
                time.sleep(self.cleanup_interval * 60)
        
        thread = threading.Thread(target=cleanup_loop, daemon=True)
        thread.start()
        logger.info(f"临时文件清理任务已启动，间隔 {self.cleanup_interval} 分钟")
```

**集成位置**: `ai_worker/interfaces/api/main.py` 的启动钩子

#### 2.2.3 优雅关闭信号处理

**文件**: `ai_worker/interfaces/workers/rabbitmq.py`

```python
import signal
import sys
import logging

logger = logging.getLogger(__name__)

def run() -> None:
    runtime = MvpWorkerRuntime(state_store=workflow_state_store)
    consumer = RabbitMqTaskConsumer(runtime)
    
    def shutdown_handler(signum, frame):
        logger.info(f"收到信号 {signum}，正在优雅关闭...")
        try:
            consumer.stop()
            runtime.cleanup()  # 清理模型、GPU 资源
            logger.info("资源清理完成")
        except Exception as exc:
            logger.error(f"清理资源时出错: {exc}", exc_info=True)
        finally:
            sys.exit(0)
    
    signal.signal(signal.SIGTERM, shutdown_handler)
    signal.signal(signal.SIGINT, shutdown_handler)
    
    logger.info("启动 RabbitMQ 消费者...")
    consumer.start_consuming()
```

### 2.3 健康检查改进

#### 2.3.1 分层健康检查端点

**文件**: `ai_worker/interfaces/api/health.py`

```python
from fastapi import APIRouter, Response
import json
import logging

router = APIRouter()
logger = logging.getLogger(__name__)

def check_models_loaded() -> str:
    """检查必需模型是否已加载"""
    try:
        from ai_worker.infrastructure.worker_runtime import runtime_instance
        if runtime_instance and runtime_instance.models_ready():
            return "ok"
        return "not_ready"
    except Exception as exc:
        logger.error(f"模型检查失败: {exc}")
        return "error"

def check_rabbitmq_alive() -> str:
    """检查 RabbitMQ 连接状态"""
    try:
        from ai_worker.infrastructure.mq.rabbitmq_consumer import consumer_instance
        if consumer_instance and consumer_instance.is_connected():
            return "ok"
        return "disconnected"
    except Exception:
        return "unknown"

def check_storage_accessible() -> str:
    """检查存储是否可写"""
    try:
        from pathlib import Path
        from ai_worker.common.config import settings
        
        test_file = Path(settings.artifact_store_root) / ".healthcheck"
        test_file.write_text("ok")
        test_file.unlink()
        return "ok"
    except Exception as exc:
        logger.error(f"存储检查失败: {exc}")
        return "error"

@router.get("/internal/health")
async def health_check():
    """健康检查（liveness probe）- 进程是否存活"""
    checks = {
        "api": "ok",
        "models": check_models_loaded(),
        "rabbitmq": check_rabbitmq_alive(),
        "storage": check_storage_accessible(),
    }
    
    # 只要进程运行就返回 200，除非存储完全不可用
    all_ok = checks["storage"] == "ok"
    status_code = 200 if all_ok else 503
    
    return Response(
        content=json.dumps(checks, ensure_ascii=False),
        media_type="application/json",
        status_code=status_code,
    )

@router.get("/internal/ready")
async def readiness_check():
    """就绪检查（readiness probe）- 是否可以接受流量"""
    checks = {
        "models": check_models_loaded(),
        "rabbitmq": check_rabbitmq_alive(),
        "storage": check_storage_accessible(),
    }
    
    # 所有依赖都就绪才返回 200
    all_ready = all(v == "ok" for v in checks.values())
    status_code = 200 if all_ready else 503
    
    return Response(
        content=json.dumps(checks, ensure_ascii=False),
        media_type="application/json",
        status_code=status_code,
    )
```

### 2.4 配置变更

**文件**: `ai_worker/common/config.py`

新增配置项：

```python
class Settings(BaseSettings):
    # ... 现有配置 ...
    
    # ===== 阶段一：稳定性配置 =====
    # 重试配置
    model_load_max_retries: int = 3
    callback_retry_delays: str = "1,2,4,8,16"  # 逗号分隔的秒数
    rabbitmq_reconnect_delay: int = 5
    
    # 清理配置
    temp_file_max_age_hours: int = 24
    temp_file_cleanup_interval_minutes: int = 60
    gpu_memory_cleanup_threshold: float = 0.9  # 90%时强制清理
```

### 2.5 阶段一实施清单

- [ ] 实现 `ai_worker/common/retry.py` 重试装饰器
- [ ] 重构 `rabbitmq_consumer.py` 增加自动重连
- [ ] 增强 `callback/client.py` 回调重试逻辑
- [ ] 实现 `gpu_context.py` GPU 资源管理
- [ ] 实现 `tempfile_manager.py` 临时文件清理
- [ ] 添加信号处理到 `rabbitmq.py`
- [ ] 重构 `health.py` 健康检查端点
- [ ] 更新 `config.py` 添加新配置项
- [ ] 编写单元测试覆盖新代码
- [ ] 本地验证：模拟网络断开、模型加载失败、GPU OOM

---

## 3. 阶段二：可观测性基础

### 3.1 结构化日志（structlog）

#### 3.1.1 集成 structlog

**依赖**: 在 `pyproject.toml` 添加
```toml
dependencies = [
    # ... 现有依赖 ...
    "structlog>=24.1,<25.0",
]
```

**文件**: `ai_worker/common/logging_config.py`

```python
import structlog
import logging.config
from ai_worker.common.config import settings

def configure_logging():
    """配置结构化日志"""
    
    # 基础处理器
    processors = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.stdlib.add_logger_name,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    ]
    
    # 根据环境选择渲染器
    if settings.log_format == "development":
        # 开发环境：彩色、人类可读
        processors.append(structlog.dev.ConsoleRenderer(colors=True))
    else:
        # 生产环境：JSON、便于解析
        processors.append(structlog.processors.JSONRenderer())
    
    structlog.configure(
        processors=processors,
        wrapper_class=structlog.stdlib.BoundLogger,
        context_class=dict,
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )
    
    # 配置标准库 logging
    logging.config.dictConfig({
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "plain": {
                "()": structlog.stdlib.ProcessorFormatter,
                "processors": processors,
            },
        },
        "handlers": {
            "default": {
                "level": settings.log_level,
                "class": "logging.StreamHandler",
                "formatter": "plain",
            },
        },
        "loggers": {
            "": {
                "handlers": ["default"],
                "level": settings.log_level,
            },
        },
    })
```

**启动时调用**: 在 `ai_worker/interfaces/api/main.py` 和 `rabbitmq.py` 入口处
```python
from ai_worker.common.logging_config import configure_logging
configure_logging()
```

#### 3.1.2 上下文注入中间件

**文件**: `ai_worker/interfaces/api/middleware.py`

```python
from fastapi import Request
from structlog import contextvars
import uuid

async def logging_context_middleware(request: Request, call_next):
    """注入请求上下文到日志"""
    contextvars.clear_contextvars()
    contextvars.bind_contextvars(
        request_id=request.headers.get("X-Request-Id", str(uuid.uuid4())),
        path=request.url.path,
        method=request.method,
    )
    
    response = await call_next(request)
    return response
```

**注册**: 在 `main.py`
```python
from ai_worker.interfaces.api.middleware import logging_context_middleware
app.middleware("http")(logging_context_middleware)
```

#### 3.1.3 任务处理日志增强

**文件**: `ai_worker/infrastructure/worker_runtime.py`

```python
import structlog

class MvpWorkerRuntime:
    async def consume_message(self, raw_message: dict):
        log = structlog.get_logger(__name__)
        
        task_id = raw_message.get("taskId")
        log = log.bind(
            task_id=task_id,
            tenant_id=raw_message.get("tenantId"),
            task_type=raw_message.get("taskType"),
            pipeline_steps=raw_message.get("pipelineSteps"),
        )
        
        log.info("task_started")
        
        try:
            result = await self._process_task(raw_message)
            log.info("task_completed", result_summary=result.get("summary"))
        except Exception as exc:
            log.error(
                "task_failed",
                error_type=type(exc).__name__,
                error_message=str(exc),
                exc_info=True,
            )
            raise
```

### 3.2 Prometheus 指标扩展

#### 3.2.1 任务级指标

**文件**: `ai_worker/observability/task_metrics.py`

```python
from prometheus_client import Counter, Histogram, Gauge

# 任务处理计数
TASK_PROCESSED = Counter(
    "ai_worker_tasks_processed_total",
    "Total tasks processed",
    labelnames=("task_type", "status"),  # status: success/failed/cancelled
)

# 任务处理耗时
TASK_DURATION = Histogram(
    "ai_worker_task_duration_seconds",
    "Task processing duration",
    labelnames=("task_type", "step"),
    buckets=(1, 5, 10, 30, 60, 120, 300, 600),
)

# 队列深度（需要定期更新）
TASK_QUEUE_SIZE = Gauge(
    "ai_worker_task_queue_size",
    "Current task queue depth",
    labelnames=("queue_name",),
)

# 辅助函数
def record_task_completed(task_type: str, status: str, duration: float, step: str):
    """记录任务完成指标"""
    TASK_PROCESSED.labels(task_type=task_type, status=status).inc()
    TASK_DURATION.labels(task_type=task_type, step=step).observe(duration)
```

#### 3.2.2 模型加载指标

**文件**: `ai_worker/observability/model_metrics.py`

```python
from prometheus_client import Counter, Histogram, Gauge

# 模型加载耗时
MODEL_LOAD_DURATION = Histogram(
    "ai_worker_model_load_seconds",
    "Model loading time",
    labelnames=("model_name",),
    buckets=(0.5, 1, 5, 10, 30, 60, 120),
)

# 模型加载失败
MODEL_LOAD_FAILURES = Counter(
    "ai_worker_model_load_failures_total",
    "Model loading failures",
    labelnames=("model_name", "error_type"),
)

# 模型状态：0=not_loaded, 1=loading, 2=ready, 3=error
MODEL_STATUS = Gauge(
    "ai_worker_model_status",
    "Model status",
    labelnames=("model_name",),
)

# 辅助函数
def record_model_load_start(model_name: str):
    MODEL_STATUS.labels(model_name=model_name).set(1)

def record_model_load_success(model_name: str, duration: float):
    MODEL_LOAD_DURATION.labels(model_name=model_name).observe(duration)
    MODEL_STATUS.labels(model_name=model_name).set(2)

def record_model_load_failure(model_name: str, error_type: str):
    MODEL_LOAD_FAILURES.labels(model_name=model_name, error_type=error_type).inc()
    MODEL_STATUS.labels(model_name=model_name).set(3)
```

#### 3.2.3 回调指标

**文件**: `ai_worker/observability/callback_metrics.py`

```python
from prometheus_client import Counter, Histogram

# 回调请求计数
CALLBACK_REQUESTS = Counter(
    "ai_worker_callback_requests_total",
    "Callback requests sent",
    labelnames=("endpoint", "status_code"),
)

# 回调耗时
CALLBACK_DURATION = Histogram(
    "ai_worker_callback_duration_seconds",
    "Callback request duration",
    labelnames=("endpoint",),
    buckets=(0.1, 0.5, 1, 2, 5, 10),
)

# 回调重试
CALLBACK_RETRIES = Counter(
    "ai_worker_callback_retries_total",
    "Callback retry attempts",
    labelnames=("endpoint", "attempt"),
)

# 辅助函数
def record_callback_request(endpoint: str, status_code: int, duration: float):
    CALLBACK_REQUESTS.labels(endpoint=endpoint, status_code=status_code).inc()
    CALLBACK_DURATION.labels(endpoint=endpoint).observe(duration)

def record_callback_retry(endpoint: str, attempt: int):
    CALLBACK_RETRIES.labels(endpoint=endpoint, attempt=str(attempt)).inc()
```

### 3.3 本地监控面板

#### 3.3.1 Prometheus 配置

**文件**: `infra/meeting-infra/observability/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'ai-worker'
    static_configs:
      - targets: ['ai-worker:8091']
        labels:
          instance: 'dev'
    scrape_interval: 10s
```

#### 3.3.2 Grafana Dashboard

**文件**: `infra/meeting-infra/observability/grafana/dashboards/ai-worker-dev.json`

简化版 dashboard 定义（关键面板）：

```json
{
  "title": "AI Worker - 开发监控",
  "panels": [
    {
      "title": "任务处理速率",
      "type": "graph",
      "targets": [
        {
          "expr": "rate(ai_worker_tasks_processed_total[5m])"
        }
      ]
    },
    {
      "title": "GPU 内存使用",
      "type": "graph",
      "targets": [
        {
          "expr": "ai_worker_gpu_memory_used_bytes / ai_worker_gpu_memory_total_bytes * 100"
        }
      ]
    },
    {
      "title": "任务耗时 P95",
      "type": "graph",
      "targets": [
        {
          "expr": "histogram_quantile(0.95, rate(ai_worker_task_duration_seconds_bucket[5m]))"
        }
      ]
    },
    {
      "title": "错误率",
      "type": "graph",
      "targets": [
        {
          "expr": "rate(ai_worker_step_failures_total[5m])"
        }
      ]
    },
    {
      "title": "模型状态",
      "type": "stat",
      "targets": [
        {
          "expr": "ai_worker_model_status"
        }
      ]
    },
    {
      "title": "回调重试率",
      "type": "graph",
      "targets": [
        {
          "expr": "rate(ai_worker_callback_retries_total[5m])"
        }
      ]
    }
  ]
}
```

#### 3.3.3 docker-compose 监控集成

**文件**: `infra/meeting-infra/docker/compose/docker-compose.observability.yml`

```yaml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:v2.45.0
    container_name: meeting-prometheus
    volumes:
      - ../observability/prometheus:/etc/prometheus
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.console.libraries=/usr/share/prometheus/console_libraries'
      - '--web.console.templates=/usr/share/prometheus/consoles'
    ports:
      - "9090:9090"
    restart: unless-stopped
    networks:
      - meeting-network
  
  grafana:
    image: grafana/grafana:10.0.0
    container_name: meeting-grafana
    volumes:
      - ../observability/grafana/provisioning:/etc/grafana/provisioning
      - ../observability/grafana/dashboards:/var/lib/grafana/dashboards
      - grafana_data:/var/lib/grafana
    environment:
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Admin
      - GF_SECURITY_ADMIN_PASSWORD=admin
    ports:
      - "3000:3000"
    restart: unless-stopped
    networks:
      - meeting-network

volumes:
  prometheus_data:
  grafana_data:

networks:
  meeting-network:
    external: true
```

#### 3.3.4 快速启动脚本

**文件**: `scripts/dev-monitor.sh`

```bash
#!/bin/bash
set -e

echo "🚀 启动本地监控栈..."

# 创建网络（如果不存在）
docker network create meeting-network 2>/dev/null || true

# 启动监控服务
docker compose \
  -f infra/meeting-infra/docker/compose/docker-compose.observability.yml \
  up -d

echo ""
echo "✅ 监控已启动："
echo "   Prometheus: http://localhost:9090"
echo "   Grafana:    http://localhost:3000 (admin/admin)"
echo ""
echo "提示："
echo "  - ai-worker 需要暴露指标端口 8091"
echo "  - 首次访问 Grafana 后导入 dashboard (ai-worker-dev.json)"
echo ""
echo "停止监控: docker compose -f infra/meeting-infra/docker/compose/docker-compose.observability.yml down"
```

### 3.4 配置变更

**文件**: `ai_worker/common/config.py`

新增配置项：

```python
class Settings(BaseSettings):
    # ... 现有配置 ...
    
    # ===== 阶段二：可观测性配置 =====
    # 日志配置
    log_level: str = "INFO"
    log_format: str = "development"  # development/production
    
    # 指标配置
    prometheus_enabled: bool = True
    prometheus_port: int = 8091
    metrics_push_interval: int = 15  # 秒
```

### 3.5 阶段二实施清单

- [ ] 添加 `structlog` 依赖到 `pyproject.toml`
- [ ] 实现 `logging_config.py` 结构化日志配置
- [ ] 实现 `middleware.py` 日志上下文中间件
- [ ] 更新 `worker_runtime.py` 使用 structlog
- [ ] 实现 `task_metrics.py` 任务指标
- [ ] 实现 `model_metrics.py` 模型指标
- [ ] 实现 `callback_metrics.py` 回调指标
- [ ] 在模型加载、任务处理、回调中集成指标记录
- [ ] 创建 `prometheus.yml` 配置
- [ ] 创建 `ai-worker-dev.json` Grafana dashboard
- [ ] 创建 `docker-compose.observability.yml`
- [ ] 编写 `dev-monitor.sh` 启动脚本
- [ ] 更新 `config.py` 添加日志和指标配置
- [ ] 本地测试：启动监控栈，查看日志和指标

---

## 4. 阶段三：容器化优化

### 4.1 多阶段构建优化

#### 4.1.1 优化的 Dockerfile

**文件**: `apps/ai-worker/Dockerfile.optimized`

```dockerfile
# syntax=docker/dockerfile:1.6

# ===== Stage 1: 基础依赖层 (缓存友好) =====
FROM nvidia/cuda:12.1.0-cudnn8-runtime-ubuntu22.04 AS base

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3.11 python3.11-venv \
    curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sf /usr/bin/python3.11 /usr/local/bin/python

# 安装 uv
RUN curl -LsSf https://astral.sh/uv/install.sh | sh \
    && ln -s /root/.local/bin/uv /usr/local/bin/uv

# ===== Stage 2: 依赖安装层 =====
FROM base AS deps

WORKDIR /app
COPY apps/ai-worker/pyproject.toml apps/ai-worker/uv.lock ./

ARG UV_EXTRAS=""
RUN extras="" \
    && for p in $(echo "$UV_EXTRAS" | tr ',' ' '); do \
         extras="$extras --extra $p"; \
       done \
    && if [ -n "$UV_EXTRAS" ]; then \
         uv sync --frozen --no-dev $extras; \
       else \
         uv sync --frozen --no-dev $extras || uv sync --no-dev $extras; \
       fi

# ===== Stage 3: Web 构建层 =====
FROM node:20-alpine AS web-build

WORKDIR /web
COPY apps/ai-worker-web/package*.json ./
RUN npm ci --ignore-scripts --loglevel=error
COPY apps/ai-worker-web/ ./
RUN npm run build && ls -la dist

# ===== Stage 4: 运行时层（最小化）=====
FROM base AS runtime

WORKDIR /app

# 只复制虚拟环境和源码
COPY --from=deps /app/.venv /app/.venv
COPY apps/ai-worker/ai_worker ./ai_worker
COPY --from=web-build /web/dist /app/admin-ui

ENV PATH="/app/.venv/bin:$PATH" \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    AI_WORKER_ADMIN_UI_DIST_PATH=/app/admin-ui

RUN useradd --uid 1001 --create-home --shell /bin/false ai-worker \
    && mkdir -p /opt/models /app/.artifacts /var/lib/ai-worker /tmp/ai-worker-temp \
    && chown -R ai-worker:ai-worker /app /opt/models /var/lib/ai-worker /tmp/ai-worker-temp

USER ai-worker
EXPOSE 8090 8091

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fs http://localhost:8090/internal/health || exit 1

ENTRYPOINT ["/app/.venv/bin/python", "-m", "ai_worker.interfaces.api.main"]

# ===== Stage 5: 开发镜像 (包含调试工具) =====
FROM runtime AS dev

USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    vim htop strace iputils-ping net-tools \
    && rm -rf /var/lib/apt/lists/*

USER ai-worker

ENV AI_WORKER_LOG_FORMAT=development \
    AI_WORKER_USE_FAKE_RUNTIME=true \
    AI_WORKER_USE_FAKE_ASR_RUNTIME=true \
    AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=true
```

**对比优化效果**:
- 原镜像: ~8GB
- 优化后 runtime: ~4-5GB
- 优化后 dev: ~5-6GB

#### 4.1.2 分层构建脚本

**文件**: `scripts/build-images.sh`

```bash
#!/bin/bash
set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

echo "🐳 构建 ai-worker 镜像..."

# 生产镜像（最小）
echo "📦 构建生产镜像 (runtime)..."
docker build \
  --target runtime \
  --build-arg UV_EXTRAS=real-models \
  -t ai-worker:latest \
  -t ai-worker:prod \
  -f apps/ai-worker/Dockerfile.optimized \
  .

# 开发镜像（包含调试工具）
echo "📦 构建开发镜像 (dev)..."
docker build \
  --target dev \
  --build-arg UV_EXTRAS="" \
  -t ai-worker:dev \
  -f apps/ai-worker/Dockerfile.optimized \
  .

echo ""
echo "✅ 镜像构建完成："
docker images | grep ai-worker | head -2
echo ""
echo "使用方式："
echo "  开发: docker run --rm -it ai-worker:dev"
echo "  生产: docker run --rm -it ai-worker:latest"
```

### 4.2 docker-compose 增强

#### 4.2.1 基础服务配置

**文件**: `infra/meeting-infra/docker/compose/docker-compose.base.yml`

```yaml
version: '3.8'

services:
  postgres:
    image: pgvector/pgvector:pg15
    container_name: meeting-postgres
    environment:
      POSTGRES_DB: meeting
      POSTGRES_USER: meeting
      POSTGRES_PASSWORD: meeting_dev
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U meeting"]
      interval: 5s
      timeout: 3s
      retries: 5
    networks:
      - meeting-network

  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    container_name: meeting-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: meeting
      RABBITMQ_DEFAULT_PASS: meeting_dev
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
      - ./rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins
    ports:
      - "5672:5672"
      - "15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - meeting-network

  minio:
    image: minio/minio:RELEASE-2024-01-01T16-36-33Z
    container_name: meeting-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - minio_data:/data
    ports:
      - "9000:9000"
      - "9001:9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 10s
      timeout: 5s
      retries: 3
    networks:
      - meeting-network

  vault:
    image: hashicorp/vault:1.15
    container_name: meeting-vault
    environment:
      VAULT_DEV_ROOT_TOKEN_ID: dev-root-token
      VAULT_DEV_LISTEN_ADDRESS: 0.0.0.0:8200
    ports:
      - "8200:8200"
    cap_add:
      - IPC_LOCK
    networks:
      - meeting-network

volumes:
  postgres_data:
  rabbitmq_data:
  minio_data:

networks:
  meeting-network:
    driver: bridge
```

#### 4.2.2 开发环境覆盖

**文件**: `infra/meeting-infra/docker/compose/docker-compose.dev.yml`

```yaml
version: '3.8'

services:
  ai-worker:
    build:
      context: ../../../..
      dockerfile: apps/ai-worker/Dockerfile.optimized
      target: dev
      args:
        UV_EXTRAS: ""
    image: ai-worker:dev
    container_name: meeting-ai-worker-dev
    environment:
      # 运行模式
      AI_WORKER_USE_FAKE_RUNTIME: "true"
      AI_WORKER_USE_FAKE_ASR_RUNTIME: "true"
      AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME: "true"
      AI_WORKER_LOG_FORMAT: development
      AI_WORKER_LOG_LEVEL: DEBUG
      
      # 依赖服务
      AI_WORKER_RABBITMQ_HOST: rabbitmq
      AI_WORKER_MEETING_API_BASE_URL: http://host.docker.internal:8080
      AI_WORKER_STORAGE_BACKEND: local
      
      # 秘钥
      AI_WORKER_CALLBACK_HMAC_SECRET: dev-secret
      AI_WORKER_INTERNAL_API_HMAC_SECRET: dev-internal-secret
      AI_WORKER_ADMIN_JWT_SECRET: dev-admin-secret-32-bytes-fixedXX
    ports:
      - "8090:8090"
      - "8091:8091"  # Prometheus metrics
    volumes:
      # 热重载源码
      - ../../../../apps/ai-worker/ai_worker:/app/ai_worker:ro
      # 模型缓存（可选）
      - ${HOME}/.cache/huggingface:/home/ai-worker/.cache/huggingface
      # 临时文件
      - ai-worker-temp:/tmp/ai-worker-temp
    depends_on:
      rabbitmq:
        condition: service_healthy
      minio:
        condition: service_healthy
    networks:
      - meeting-network
    command: ["uvicorn", "ai_worker.interfaces.api.main:app", "--reload", "--host", "0.0.0.0", "--port", "8090"]

volumes:
  ai-worker-temp:

networks:
  meeting-network:
    external: true
```

#### 4.2.3 一键启动脚本

**文件**: `scripts/dev-up.sh`

```bash
#!/bin/bash
set -e

COMPOSE_BASE="infra/meeting-infra/docker/compose/docker-compose.base.yml"
COMPOSE_DEV="infra/meeting-infra/docker/compose/docker-compose.dev.yml"
COMPOSE_OBS="infra/meeting-infra/docker/compose/docker-compose.observability.yml"

echo "🚀 启动开发环境..."

# 创建网络
docker network create meeting-network 2>/dev/null || true

# 1. 启动基础服务
echo "📦 启动基础服务 (PostgreSQL, RabbitMQ, MinIO, Vault)..."
docker compose -f "$COMPOSE_BASE" up -d

# 2. 等待服务就绪
echo "⏳ 等待服务就绪..."
for i in {1..30}; do
  if docker compose -f "$COMPOSE_BASE" ps | grep -q "(healthy)"; then
    echo "✓ 基础服务已就绪"
    break
  fi
  sleep 1
done

# 3. 启动 ai-worker
echo "📦 启动 ai-worker..."
docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEV" up -d ai-worker

# 4. 可选：启动监控栈
if [ "$1" == "--with-monitoring" ] || [ "$1" == "-m" ]; then
  echo "📊 启动监控栈..."
  docker compose -f "$COMPOSE_OBS" up -d
fi

echo ""
echo "✅ 开发环境已启动："
echo ""
echo "服务地址："
echo "  ai-worker:  http://localhost:8090"
echo "  Metrics:    http://localhost:8091/metrics"
echo "  RabbitMQ:   http://localhost:15672 (meeting/meeting_dev)"
echo "  MinIO:      http://localhost:9001 (minioadmin/minioadmin)"
echo "  Vault:      http://localhost:8200 (token: dev-root-token)"

if [ "$1" == "--with-monitoring" ] || [ "$1" == "-m" ]; then
  echo "  Prometheus: http://localhost:9090"
  echo "  Grafana:    http://localhost:3000 (admin/admin)"
fi

echo ""
echo "常用命令："
echo "  查看日志: docker compose -f $COMPOSE_DEV logs -f ai-worker"
echo "  重启:     docker compose -f $COMPOSE_DEV restart ai-worker"
echo "  停止:     ./scripts/dev-down.sh"
```

**文件**: `scripts/dev-down.sh`

```bash
#!/bin/bash
set -e

COMPOSE_BASE="infra/meeting-infra/docker/compose/docker-compose.base.yml"
COMPOSE_DEV="infra/meeting-infra/docker/compose/docker-compose.dev.yml"
COMPOSE_OBS="infra/meeting-infra/docker/compose/docker-compose.observability.yml"

echo "🛑 停止开发环境..."

docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEV" down
docker compose -f "$COMPOSE_OBS" down 2>/dev/null || true

echo "✅ 开发环境已停止"
echo ""
echo "提示: 数据卷已保留，下次启动时数据仍在"
echo "      如需完全清理: docker compose -f $COMPOSE_BASE down -v"
```

### 4.3 配置管理改进

#### 4.3.1 分层配置文件

创建配置模板：

**文件**: `apps/ai-worker/.env.example`

```bash
# AI Worker 配置模板
# 复制此文件为 .env.local 并填写实际值

# ===== 基础配置 =====
AI_WORKER_WORKER_ID=worker_dev_001
AI_WORKER_LOG_LEVEL=INFO
AI_WORKER_LOG_FORMAT=development  # development/production

# ===== RabbitMQ =====
AI_WORKER_RABBITMQ_HOST=localhost
AI_WORKER_RABBITMQ_PORT=5672
AI_WORKER_RABBITMQ_USERNAME=meeting
AI_WORKER_RABBITMQ_PASSWORD=meeting_dev
AI_WORKER_RABBITMQ_TASK_QUEUES=audio-cpu-queue,gpu-asr-queue,gpu-diar-queue,gpu-speaker-queue,embed-queue

# ===== 回调配置 =====
AI_WORKER_MEETING_API_BASE_URL=http://localhost:8080
AI_WORKER_CALLBACK_HMAC_SECRET=dev-secret
AI_WORKER_INTERNAL_API_HMAC_SECRET=dev-internal-secret

# ===== 存储配置 =====
AI_WORKER_STORAGE_BACKEND=local  # local/tos
AI_WORKER_ARTIFACT_STORE_ROOT=.artifacts

# ===== 模型运行时 =====
AI_WORKER_USE_FAKE_RUNTIME=true
AI_WORKER_USE_FAKE_ASR_RUNTIME=true
AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=true

# ===== 监控配置 =====
AI_WORKER_PROMETHEUS_ENABLED=true
AI_WORKER_PROMETHEUS_PORT=8091

# ===== 重试配置 =====
AI_WORKER_MODEL_LOAD_MAX_RETRIES=3
AI_WORKER_CALLBACK_RETRY_DELAYS=1,2,4,8,16
AI_WORKER_RABBITMQ_RECONNECT_DELAY=5

# ===== 清理配置 =====
AI_WORKER_TEMP_FILE_MAX_AGE_HOURS=24
AI_WORKER_TEMP_FILE_CLEANUP_INTERVAL_MINUTES=60
```

**文件**: `apps/ai-worker/configs/development.env`

```bash
# 开发环境默认配置
AI_WORKER_LOG_FORMAT=development
AI_WORKER_LOG_LEVEL=DEBUG
AI_WORKER_USE_FAKE_RUNTIME=true
AI_WORKER_USE_FAKE_ASR_RUNTIME=true
AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=true
AI_WORKER_STORAGE_BACKEND=local
```

**文件**: `apps/ai-worker/configs/production.env`

```bash
# 生产环境配置示例
AI_WORKER_LOG_FORMAT=production
AI_WORKER_LOG_LEVEL=INFO
AI_WORKER_USE_FAKE_RUNTIME=false
AI_WORKER_USE_FAKE_ASR_RUNTIME=false
AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false
AI_WORKER_STORAGE_BACKEND=tos
AI_WORKER_PROMETHEUS_ENABLED=true
```

#### 4.3.2 配置加载优先级

**文件**: `ai_worker/common/config.py` 修改

```python
import os
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    # ... 所有配置字段 ...
    
    model_config = SettingsConfigDict(
        env_prefix="AI_WORKER_",
        env_file=(
            ".env.local",                                    # 优先级最高（不提交）
            f"configs/{os.getenv('ENV', 'development')}.env",  # 环境特定配置
            ".env",                                          # 通用配置
        ),
        env_file_encoding="utf-8",
        extra="ignore",  # 忽略未定义的环境变量
    )

settings = Settings()
```

#### 4.3.3 配置验证工具

**文件**: `scripts/validate-config.sh`

```bash
#!/bin/bash
set -e

cd "$(dirname "$0")/.."

echo "🔍 验证 ai-worker 配置..."

uv run python -c "
from ai_worker.common.config import settings
import sys

# 必需配置检查
required = {
    'rabbitmq_host': settings.rabbitmq_host,
    'meeting_api_base_url': settings.meeting_api_base_url,
    'callback_hmac_secret': settings.callback_hmac_secret,
}

missing = [k for k, v in required.items() if not v]
if missing:
    print(f'❌ 缺少必需配置: {missing}')
    sys.exit(1)

# 配置合理性检查
if settings.callback_retry_delays:
    delays = [int(x.strip()) for x in settings.callback_retry_delays.split(',')]
    if any(d < 0 for d in delays):
        print('❌ callback_retry_delays 不能包含负数')
        sys.exit(1)

if settings.temp_file_max_age_hours < 1:
    print('⚠️  警告: temp_file_max_age_hours 小于 1 小时')

print('✅ 配置验证通过')
print(f'   环境: {settings.log_format}')
print(f'   日志级别: {settings.log_level}')
print(f'   Fake 运行时: {settings.use_fake_runtime}')
"
```

### 4.4 快速启动工具集

**文件**: `scripts/dev.sh` (统一入口)

```bash
#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

case "$1" in
  up)
    "$SCRIPT_DIR/dev-up.sh" "$2"
    ;;
  down)
    "$SCRIPT_DIR/dev-down.sh"
    ;;
  logs)
    docker compose -f infra/meeting-infra/docker/compose/docker-compose.dev.yml logs -f ai-worker
    ;;
  restart)
    docker compose -f infra/meeting-infra/docker/compose/docker-compose.dev.yml restart ai-worker
    ;;
  shell)
    docker compose -f infra/meeting-infra/docker/compose/docker-compose.dev.yml exec ai-worker bash
    ;;
  test)
    cd apps/ai-worker && uv run pytest tests/ -v
    ;;
  build)
    "$SCRIPT_DIR/build-images.sh"
    ;;
  monitor)
    "$SCRIPT_DIR/dev-monitor.sh"
    ;;
  validate)
    "$SCRIPT_DIR/validate-config.sh"
    ;;
  *)
    echo "用法: ./scripts/dev.sh <command> [options]"
    echo ""
    echo "命令:"
    echo "  up [-m]       启动开发环境 (-m 包含监控)"
    echo "  down          停止开发环境"
    echo "  logs          查看 ai-worker 日志"
    echo "  restart       重启 ai-worker"
    echo "  shell         进入 ai-worker 容器"
    echo "  test          运行测试"
    echo "  build         构建镜像"
    echo "  monitor       启动监控栈"
    echo "  validate      验证配置"
    exit 1
    ;;
esac
```

### 4.5 阶段三实施清单

- [ ] 创建 `Dockerfile.optimized` 多阶段构建
- [ ] 编写 `build-images.sh` 构建脚本
- [ ] 创建 `docker-compose.base.yml` 基础服务
- [ ] 创建 `docker-compose.dev.yml` 开发覆盖
- [ ] 编写 `dev-up.sh` 启动脚本
- [ ] 编写 `dev-down.sh` 停止脚本
- [ ] 创建 `.env.example` 配置模板
- [ ] 创建 `configs/development.env` 和 `configs/production.env`
- [ ] 更新 `config.py` 支持分层配置加载
- [ ] 编写 `validate-config.sh` 配置验证
- [ ] 创建 `dev.sh` 统一入口脚本
- [ ] 本地测试：构建镜像、启动容器、验证热重载
- [ ] 文档更新：README.md 添加快速启动说明

---

## 5. 测试策略

### 5.1 单元测试

**新增测试文件**:

```python
# tests/common/test_retry.py
def test_retry_decorator_success():
    """测试重试装饰器正常情况"""
    pass

def test_retry_decorator_with_retries():
    """测试重试装饰器重试机制"""
    pass

def test_exponential_backoff():
    """测试指数退避计算"""
    pass

# tests/common/test_gpu_context.py
def test_gpu_context_cleanup():
    """测试 GPU 上下文清理"""
    pass

# tests/common/test_tempfile_manager.py
def test_cleanup_old_files():
    """测试临时文件清理"""
    pass

# tests/observability/test_metrics.py
def test_task_metrics_recording():
    """测试任务指标记录"""
    pass

def test_model_metrics_recording():
    """测试模型指标记录"""
    pass
```

### 5.2 集成测试

**测试场景**:

1. **RabbitMQ 重连测试**
   - 启动 ai-worker
   - 停止 RabbitMQ
   - 验证重连日志
   - 重启 RabbitMQ
   - 验证自动恢复

2. **模型加载重试测试**
   - 模拟网络超时
   - 验证重试行为
   - 验证最终成功或失败

3. **健康检查测试**
   - 模拟模型未加载
   - 验证 `/internal/health` 返回 503
   - 验证 `/internal/ready` 返回 503

4. **日志结构化测试**
   - 发送任务
   - 验证日志包含 task_id、tenant_id
   - 验证 JSON 格式可解析

### 5.3 手动验证清单

**阶段一验证**:
- [ ] 断开网络后模型加载重试
- [ ] RabbitMQ 重启后自动重连
- [ ] GPU 内存在任务后释放
- [ ] 临时文件在 24 小时后清理
- [ ] Ctrl+C 优雅关闭

**阶段二验证**:
- [ ] 日志包含上下文信息
- [ ] Prometheus `/metrics` 端点可访问
- [ ] Grafana dashboard 显示数据
- [ ] 任务失败时指标计数增加

**阶段三验证**:
- [ ] 镜像体积减小到 ~5GB
- [ ] 代码修改后热重载生效
- [ ] `./scripts/dev.sh up` 一键启动
- [ ] 监控面板正常工作

---

## 6. 回滚方案

### 6.1 阶段回滚

如果某个阶段出现问题，可以独立回滚：

**阶段一回滚**:
```bash
git revert <commit-hash>  # 回滚错误处理相关代码
# 或直接删除新增的 retry.py、gpu_context.py 等文件
```

**阶段二回滚**:
```bash
# 移除 structlog 依赖
uv remove structlog
# 恢复原有 logging 代码
git checkout HEAD~1 -- ai_worker/common/logging_config.py
```

**阶段三回滚**:
```bash
# 使用原有 Dockerfile
docker build -f apps/ai-worker/Dockerfile .
# 或删除新的 compose 文件，使用原有启动方式
```

### 6.2 配置兼容性

所有新增配置都有默认值，确保：
- 不设置新配置时，行为与优化前一致
- 逐步启用新特性，降低风险

---

## 7. 文档更新

### 7.1 README.md 更新

在 `apps/ai-worker/README.md` 添加：

```markdown
## 快速启动

### 方式一：docker-compose (推荐)

```bash
# 启动开发环境
./scripts/dev.sh up

# 启动并包含监控
./scripts/dev.sh up -m

# 查看日志
./scripts/dev.sh logs

# 停止
./scripts/dev.sh down
```

### 方式二：本地运行

```bash
# 安装依赖
uv sync --extra dev

# 启动 API
uv run ai-worker-api

# 启动消费者
uv run ai-worker-consumer
```

## 监控

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)
- Metrics: http://localhost:8091/metrics

## 配置

配置优先级：`.env.local` > `configs/{ENV}.env` > `.env`

查看配置模板：`cat .env.example`
```

### 7.2 CLAUDE.md 更新

在 `apps/ai-worker/CLAUDE.md` 添加：

```markdown
## 开发环境

快速启动（推荐）：
```bash
./scripts/dev.sh up          # 启动开发环境
./scripts/dev.sh up -m       # 启动 + 监控
./scripts/dev.sh logs        # 查看日志
```

## 监控和日志

- 结构化日志：使用 structlog，开发环境彩色输出，生产环境 JSON
- Prometheus 指标：http://localhost:8091/metrics
- Grafana 面板：http://localhost:3000

## 常见问题

**Q: 模型加载失败？**
A: 检查 `AI_WORKER_USE_FAKE_*_RUNTIME` 是否为 true（开发环境默认）

**Q: RabbitMQ 连接断开？**
A: 会自动重连，查看日志确认
```

---

## 8. 实施时间表

| 阶段 | 工作日 | 关键里程碑 |
|------|--------|-----------|
| 阶段一 | 1-2天 | 错误处理、资源清理、健康检查 ✅ |
| 阶段二 | 1-2天 | 结构化日志、指标、监控面板 ✅ |
| 阶段三 | 1天 | Dockerfile 优化、compose 增强 ✅ |
| **总计** | **3-5天** | 全部优化完成 |

---

## 9. 成功指标

优化完成后，应达到：

1. **稳定性指标**
   - [ ] 模型加载成功率 > 99%（含重试）
   - [ ] RabbitMQ 连接中断后 < 10 秒恢复
   - [ ] GPU OOM 后自动重启，无需人工干预
   - [ ] 零临时文件泄漏（24 小时清理）

2. **可观测性指标**
   - [ ] 所有关键路径有日志覆盖
   - [ ] Prometheus 指标涵盖任务/模型/回调
   - [ ] Grafana 面板可视化关键指标
   - [ ] 故障排查时间 < 5 分钟

3. **开发体验指标**
   - [ ] 环境启动时间 < 2 分钟
   - [ ] 代码热重载 < 5 秒
   - [ ] 镜像构建时间 < 10 分钟
   - [ ] 镜像体积 < 5GB

---

## 10. 附录

### 10.1 依赖版本

```toml
[project.dependencies]
# ... 现有依赖 ...
structlog = ">=24.1,<25.0"

[project.optional-dependencies]
dev = [
    # ... 现有依赖 ...
    "respx>=0.21",  # 用于测试 HTTP 客户端
]
```

### 10.2 环境变量速查表

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AI_WORKER_LOG_FORMAT` | `development` | `development` / `production` |
| `AI_WORKER_LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |
| `AI_WORKER_MODEL_LOAD_MAX_RETRIES` | `3` | 模型加载重试次数 |
| `AI_WORKER_CALLBACK_RETRY_DELAYS` | `1,2,4,8,16` | 回调重试延迟（秒） |
| `AI_WORKER_RABBITMQ_RECONNECT_DELAY` | `5` | RabbitMQ 重连延迟（秒） |
| `AI_WORKER_TEMP_FILE_MAX_AGE_HOURS` | `24` | 临时文件最大保留时间 |
| `AI_WORKER_PROMETHEUS_PORT` | `8091` | Prometheus 指标端口 |

### 10.3 相关文档链接

- [structlog 文档](https://www.structlog.org/)
- [Prometheus Python 客户端](https://github.com/prometheus/client_python)
- [Docker 多阶段构建](https://docs.docker.com/build/building/multi-stage/)
- [pika 重连模式](https://pika.readthedocs.io/en/stable/examples/heartbeat_and_blocked_timeouts.html)

---

**文档完成日期**: 2026-06-16  
**预计实施完成**: 2026-06-21  
**负责人**: 开发团队

