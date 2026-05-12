# ai-worker Spec

## 1. 工程定位

`ai-worker` 是独立部署的 Python AI 计算层，运行在独立 GPU 机器上。它负责音频处理、本地 ASR、说话人分离、声纹 embedding、文本 embedding 和必要的 workflow 编排。

`ai-worker` 不直接写 Java 业务库，不自行判断用户业务权限。所有业务结果通过 `meeting-api` internal callback API 回写。

## 2. 技术栈

一期固定采用：

```text
Python 3.11
uv 严格 lock
FastAPI 0.115+ / Uvicorn，不使用 gunicorn
Clean Architecture
Dramatiq 1.17+ WorkerRuntime，broker 使用 RabbitMQ
Prefect 3.x WorkflowEngine
LangGraph Agent
ffmpeg / ffprobe
torch 2.5+ / transformers 4.45+ / pyannote.audio 3.3+ / 3D-Speaker
soundfile / librosa / ffmpeg-python
RabbitMQ client
volcengine-tos-python-sdk
Java callback client
pytest / pytest-asyncio / respx / pytest-benchmark
prometheus-client / structlog
```

Pipeline 业务逻辑必须通过 `WorkerRuntime`、`WorkflowEngine`、`ModelRuntime`、`ArtifactStore`、`CallbackClient` 等端口隔离具体 SDK。Celery、Temporal、Ray、独立 model server 都是后续替换选项，不进入一期默认实现。

## 2.1 开发准入

`ai-worker` 先实现可替换端口和 callback 闭环，再接入重模型：

1. MVP-0：校验 RabbitMQ task message、workflow registry、step 集合、HMAC callback client 和 fake / smoke pipeline，能把 step 状态回写 `meeting-api`。
2. MVP-1：接入音频预处理、VAD、ASR、Diarization、speaker embedding、transcript merge 和 artifact manifest。
3. MVP-2：接入 text embedding、RAG indexing、rerank lazy-load、模型 checksum、GPU 指标和性能验收。

一期不创建独立 `gpu-align-queue` 或 `rerank-queue`。Forced Alignment 只在需要精确时间戳时由 workflow 进程内按需执行；Rerank 模型在 `model_runtime` 内 lazy-load，通过 FastAPI `POST /internal/rerank` 为 Java RAG query 提供同步精排能力。只有后续需要独立扩容或 GPU 调度隔离时，才新增对应 worker 队列。

## 3. 包结构

```text
ai_worker/
  interfaces/
    api/                FastAPI 内部管理接口
    workers/            RabbitMQ / Celery / Dramatiq 适配
    callbacks/          callback payload 适配
  application/
    use_cases/          用例编排
    workflows/          Pipeline DAG
    agents/             LangGraph Agent
  domain/
    audio/              音频、channel、质量、VAD 领域对象
    transcript/         ASR、segment、merge 领域对象
    speaker/            speaker label、embedding、候选匹配
    task/               task、step、attempt、lease
    knowledge/          chunk、embedding request、artifact
  infrastructure/
    storage/            TOS client
    mq/                 RabbitMQ runtime
    workflow/           Prefect / Temporal 实现
    llm_gateway/        经 Java llm-gateway 的调用端口
    java_callback/      internal callback client
  pipeline/
    audio/
    asr/
    alignment/
    diarization/
    speaker/
    embedding/
    rag_indexing/
  model_runtime/
    asr/
    diarization/
    speaker/
    embedding/
    rerank/
  common/
```

## 4. FastAPI 内部接口

FastAPI 只作为内部管理、健康检查、模型状态和调试入口，不作为客户主产品入口。

一期接口：

```http
GET /internal/health
GET /internal/models
GET /internal/workflows/{task_id}
POST /internal/rerank
```

`POST /internal/rerank` 是 Java `meeting-api` 到 `ai-worker` 的同步内部调用，只服务 RAG query-time rerank，不作为前端 API。请求 / 响应 schema 以 `packages/meeting-contracts/openapi/ai-worker-internal-api.yaml` 为准。调用要求：

1. 仅允许内网 + HMAC 请求，签名算法和 signing string 结构与 callback 同源，但使用独立方向密钥。`meeting.ai-worker.hmac-secret` / worker inbound secret 用于 Java -> ai-worker rerank；callback HMAC secret 只用于 ai-worker -> Java callback，二者不得复用。
2. 请求必须携带 `tenantId`、`requestId`、`traceId`、query 文本和已授权候选 chunk。
3. `ai-worker` 不重新判断用户权限，只做模型推理和候选重排。
4. 返回每个候选的 `rerankScore` 和 `rank`，不写业务库。
5. 模型未加载时 lazy-load；加载失败返回稳定错误码，Java 决定是否降级为 RRF 排序。

配置要求：

1. worker 侧必须配置 Java -> ai-worker inbound HMAC secret，名称可按部署环境映射为 `AI_WORKER_INBOUND_HMAC_SECRET`；该值与 Java `meeting.ai-worker.hmac-secret` 相同。
2. worker 侧 callback client 使用独立的 callback HMAC secret；不得与 inbound rerank secret 共用。
3. 缺少 inbound rerank secret 时，prod / staging profile 必须拒绝 ready。

可扩展接口：

1. 上传测试音频。
2. 触发内部 ASR smoke test。
3. 查看模型加载状态、GPU 显存和版本 checksum。
4. 查看 workflow DAG、当前 step、重试和取消状态。

所有内部接口必须通过内网访问控制或内部鉴权保护，不能暴露给外部用户。

## 5. 输入任务

事实来源：RabbitMQ 任务消息 schema 以 `packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json` 为准；枚举值以 `packages/meeting-contracts/schemas/common/enums.yaml` 为准。本节只描述 `ai-worker` 的消费、校验和 fail-fast 行为。

RabbitMQ 任务消息由 `meeting-api` 创建，`ai-worker` 只消费授权后的任务。

任务字段按 `taskType` 条件校验，不得把非本任务类型的字段当作隐式必填：

| taskType | 额外必填字段 |
|---|---|
| `MEETING_FULL_PIPELINE` | `meetingId`、`audioFileId`、`audioUri`、`language`、`channelMap`、`knownParticipants`、`minSpeakers`、`maxSpeakers` |
| `TEXT_EMBEDDING` / `RAG_REINDEX` | `meetingId` 与 `documentId` 至少一个非空 |
| `SPEAKER_ENROLLMENT` | `speakerProfileId`、`speakerEnrollmentId`、`audioFileId`、`audioUri`、`language` |

所有任务共同必填 `taskId`、`taskType`、`tenantId`、`securityLevel`、`attemptNo`、`expectedInputVersion`、`options`、`traceId` 和 `pipelineSteps`。`EXPORT` 不属于 Python `ai-worker` 任务类型；导出消息使用 `packages/meeting-contracts/schemas/rabbitmq/export-job-message.schema.json`，由 Java `export-queue` consumer 处理。

缺失关键字段时，worker 应 fail fast，并通过 callback 写入稳定 `error_code`。

## 6. Pipeline DAG

`workflowId` 由 WorkflowEngine 内部生成。同一个 `taskId` 的不同 `attemptNo` 可以对应不同 `workflowId`；`taskId` 是跨 attempt 稳定的业务任务 id，Java 和前端只依赖 `taskId` 查询业务状态。

Prefect Flow 形态：

```python
@flow(name="meeting-full-pipeline", retries=0)
async def meeting_full_pipeline(task: TaskMessage) -> TranscriptArtifact:
    audio = await preprocess.submit(task)                  # CPU / IO
    vad = await vad_segment.submit(audio, task)             # CPU
    asr = await asr_recognize.submit(vad, task)             # GPU-ASR
    diar = await diarize.submit(audio, task)                # GPU-DIAR
    speaker_vectors = await speaker_embed.submit(audio, diar, task)  # GPU-SPEAKER
    candidates = await speaker_match.submit(speaker_vectors, task)   # GPU-SPEAKER
    merged = await transcript_merge.submit(asr, diar, candidates, task)
    await callback_transcript.submit(task, merged)
    if task.options.enable_rag_indexing:
        await embedding_submit.submit(merged, task)
    await callback_worker_phase_complete.submit(task, merged, phase="WORKER_DAG")
    return merged
```

`SUMMARY` 和 `EXTRACTION` 不属于 `ai-worker` Pipeline DAG；这两个 step 由 Java `meeting-api-app` 在调用 `llm-gateway` 时通过 `TaskStepProgressService` 推进，worker 不发送对应 step callback。

任务消息的 `pipelineSteps` 不得包含 `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION` / `EXPORT`：`AUDIO_UPLOAD` 由 Java 在 task 创建前完成并标记成功，`SUMMARY` / `EXTRACTION` 由 Java `meeting-api-app` 推进，`EXPORT` 由 Java `export-queue` consumer 处理。worker 启动 workflow 前必须按 `processing-task-message.schema.json` 做 fail-fast 校验；如果收到未知 step 或被禁止的非 worker step，必须终止消费并通过 `/fail` 上报 `INVALID_TASK_MESSAGE`，不得尝试发送对应 step callback。

worker 根据 `taskType` 选择 workflow 入口，并使用 `pipelineSteps` 校验该 workflow 内部 DAG。`pipelineSteps` 与 workflow registry 中声明的 step 集合必须一一对应；缺失、额外或顺序 / 依赖不满足时，worker 必须 `/fail INVALID_TASK_MESSAGE` 拒绝消费。具体映射定义在 `apps/ai-worker/ai_worker/application/workflows/registry.py` 或等效注册表，并由契约测试覆盖。

失败策略：每个 worker step 开始、进度和失败都 callback；CPU 预处理失败直接终止；ASR 失败导致 transcript 不可用；Diarization 失败必须 `FAILED`，不得降级；一期只有 `ALIGNMENT`、`RAG_INDEXING`、`SPEAKER_MATCHING` 可按配置或业务条件降级为 `PARTIAL_SUCCEEDED`；callback 失败按 `WRITEBACK_FAILED` 重试，耗尽后让 Java lease 过期重入队。

`PARTIAL_SUCCEEDED` 上报路径：

1. 可降级 step 失败且已有可用产物时，worker 继续完成可用产物回写。
2. worker 阶段完成时调用 `POST /internal/processing-tasks/{taskId}/complete`，body 必须包含 `phase=WORKER_DAG`。`status=PARTIAL_SUCCEEDED` 表示 worker phase partial，并在 `skippedSteps` 中声明失败 / 跳过的 optional step 和原因；这不是整个 task 的终态。
3. 不可降级 step 失败时调用 `/fail`，不得通过 `/complete + skippedSteps` 伪装成功。
4. `DIARIZATION`、`SPEAKER_EMBEDDING`、`TRANSCRIPT_MERGE`、`SUMMARY`、`EXTRACTION` 失败均不由 worker 降级；其中 `SUMMARY` / `EXTRACTION` 的失败由 Java 上报。

### 6.1 音频预处理

1. 使用 `ffprobe` 读取音频格式、时长、采样率、声道数和码率。
2. 单场会议音频最长 4 小时，超出返回 `AUDIO_TOO_LONG`。
3. 识别并保存 `channel_map`，保留多声道信息。
4. 只有明确为多人单通道或确认可混音时，才转为 16kHz mono WAV。
5. 音频无法读取返回 `AUDIO_CORRUPTED`。
6. 格式不支持返回 `AUDIO_UNSUPPORTED_FORMAT`。
7. 质量检测过低返回 `AUDIO_QUALITY_LOW` 或 partial result 策略。

### 6.2 VAD 与 ASR

1. VAD 先识别有效语音区间。
2. ASR 按 30 到 120 秒批处理。
3. chunk overlap 为 0.3 到 0.8 秒。
4. 保存切片策略版本、VAD 版本、ASR 模型版本和权重 checksum。
5. ASR 输出原始 JSON 写入 TOS，并作为 artifact 回写。
6. 推理异常返回 `ASR_RUNTIME_ERROR`，显存不足返回 `ASR_GPU_OOM`。

### 6.3 Diarization 与 Alignment

1. Diarization 输出 `SPEAKER_00`、`SPEAKER_01` 等匿名 label。
2. 保存 diarization turns、模型版本和置信度。
3. `ALIGNMENT` 一期默认不全量执行，只在精确引用、报告导出或人工触发时按需启用。
4. Diarization 失败返回 `DIARIZATION_FAILED`。
5. Alignment 失败返回 `ALIGNMENT_FAILED`，不得阻断已有 ASR 可查看结果，除非任务配置要求强依赖。

### 6.4 Speaker Embedding 与匹配

1. 支持参考音频 embedding 提取。
2. 支持会议 speaker label embedding 提取。
3. 只在 `knownParticipants` 或 Java 授权范围内做候选匹配，不做全公司无差别搜索。
4. embedding 不返回前端，不发送给 DashScope。
5. embedding 明文不得写入普通日志。
6. speaker-candidates callback 必须始终携带明文 `embedding.values`，不得改为仅传 `artifactUri`；明文通过 internal TLS + HMAC callback 回写 Java，由 Java 一侧 KMS 信封加密落库；`ai-worker` 不持有 KMS 凭证。
7. `ai-worker` 不得把 speaker embedding 明文写入 TOS；callback 成功或重试耗尽后必须清除进程内明文引用。
8. 生成候选时返回 speaker label、candidate person/profile、score、threshold、model version 和 artifact manifest。
9. 提取失败返回 `SPEAKER_EMBEDDING_FAILED`，匹配失败返回 `SPEAKER_MATCH_FAILED`。

### 6.5 Transcript Merge

1. 合并 ASR segment、Diarization turn、speaker label 和置信度。
2. 输出结构化转录，包含 `segmentId`、`startMs`、`endMs`、`speakerLabel`、`text`、`asrConfidence`、`diarizationConfidence`、`speakerConfidence`、`timestampPrecision`。
3. callback 到 `POST /internal/processing-tasks/{taskId}/transcript`。
4. 合并失败返回 `TRANSCRIPT_MERGE_FAILED`。

### 6.6 Embedding

1. 文本 embedding 用于会议 chunk、纪要 chunk、事项 chunk 和文档 chunk。
2. 一期默认 bge-m3 或同级多语言模型。
3. 产物通过 `POST /internal/processing-tasks/{taskId}/embeddings` 回写；小批次可直接回写向量，大批次优先写 TOS artifact URI。
4. 记录 embedding model version、checksum、chunk strategy version 和 source version。

## 7. Callback 规范

事实来源：callback endpoint、请求头、请求体和错误响应以 `packages/meeting-contracts/openapi/internal-callback-api.yaml` 为准。本节只描述 worker 发送端的签名、幂等、重试和 artifact 约束。

所有 callback 必须携带：

```http
X-Worker-Id
X-Attempt-No
X-Lease-Owner
X-Request-Id
X-Trace-Id
X-Timestamp
X-Nonce
Idempotency-Key
X-Signature
```

约束：

1. HMAC-SHA256 签名。
2. `Idempotency-Key` 对同一 task、step、attempt、payload version 稳定；`PATCH .../steps/{stepName}` 中 `status=RUNNING && progress>0` 的 heartbeat 固定使用同一 payload version，允许重复发送同 key，Java 不写幂等表。
3. callback 失败要重试，重试耗尽返回 `WRITEBACK_FAILED`。
4. 每个 step 开始、进度、完成和失败都要回写。
5. 大 JSON 和中间产物写 TOS，callback 只传 URI、sha256、size、summary 和 metadata。
6. 所有 AI 对外结果必须能追溯到 `artifact_manifest`。

heartbeat callback 版本规则：`status=RUNNING && progress>0` 的连续进度上报固定使用同一 payload version，表达 latest-wins 进度刷新，不得因为连续上报而预期 Java 返回 409。

普通 step callback 版本规则：step 开始、完成、失败以及 artifact / transcript / embeddings 等语义变化必须更换 payload version，并接受 Java 的 body hash 幂等校验。

每个 step 的触发点：

| step | callback endpoint | body schema |
|---|---|---|
| 所有 step | `PATCH /internal/processing-tasks/{taskId}/steps/{stepName}` | `StepUpdateRequest` |
| ASR / Diarization / quality | `POST /internal/processing-tasks/{taskId}/artifacts` | `ArtifactCallbackRequest` |
| TRANSCRIPT_MERGE | `POST /internal/processing-tasks/{taskId}/transcript` | `TranscriptCallbackRequest` |
| SPEAKER_MATCHING | `POST /internal/processing-tasks/{taskId}/speaker-candidates` | `SpeakerCandidatesCallbackRequest` |
| RAG_INDEXING / TEXT_EMBEDDING | `POST /internal/processing-tasks/{taskId}/embeddings` | `EmbeddingsCallbackRequest` |
| worker DAG 阶段完成 | `POST /internal/processing-tasks/{taskId}/complete`，`phase=WORKER_DAG` | `CompleteWorkerPhaseRequest` |
| 任务失败 | `POST /internal/processing-tasks/{taskId}/fail` | `FailTaskRequest` |

## 8. 模型供应链

1. 生产启动不得临时联网下载模型权重。
2. 本地权重进入内网制品库。
3. 记录模型 license、商用条款、来源 URL、checksum、审批人和发布时间。
4. `GET /internal/models` 必须能展示已加载模型、版本、checksum、device、状态和最近错误。
5. 每个 artifact manifest 记录实际模型版本和 checksum。

模型加载策略：

1. 进程启动时 eager-load ASR、Diarization、Speaker Embedding 到 GPU，并用 1 秒静音音频预热。
2. bge-m3 / reranker 按需 lazy-load，保留进程内 LRU model cache。
3. 每个 worker 进程绑定单 GPU，通过 `CUDA_VISIBLE_DEVICES` 切分。
4. OOM 后 worker 进程主动退出，由 supervisor / systemd / k8s 重启；不得在未知显存状态下继续消费任务。
5. `GET /internal/health` 只有在必需模型预热完成后才返回 ready。

GPU 并发模型：

1. ASR、Diarization、Speaker Embedding 各自一个 Dramatiq actor，单 GPU `concurrency=1`。
2. 同一 GPU 上禁止 ASR 与 Diarization 并行抢显存；通过队列和 actor 拓扑串行化 GPU step。
3. 多 GPU 部署时每块 GPU 起一组 actor，通过 routing key 或 worker name 分发。
4. CPU step 可以并发，默认并发数为 `min(4, cpu_count / 2)`。

## 9. 性能目标

一期最低 GPU：

```text
RTX 3090 / 4090 24GB 或同等显存 GPU
```

目标：

1. ASR RTF <= 0.3。
2. Diarization RTF <= 0.4。
3. 60 分钟会议端到端目标 <= 30 分钟；超过 45 分钟告警；超过 60 分钟必须记录 RTF 原因和瓶颈 step。
4. ASR 和 Diarization 单 GPU 默认并发为 1。
5. OOM 需要稳定错误码、自动释放资源并允许重试。

60 分钟音频预算：

| step | 预算 |
|---|---:|
| `AUDIO_PREPROCESS` | <= 60s |
| `ASR` | RTF <= 0.3，<= 18min |
| `DIARIZATION` | RTF <= 0.4，<= 24min，可与 ASR 在多 GPU 并发 |
| `SPEAKER_EMBEDDING` + `SPEAKER_MATCHING` | <= 60s |
| `TRANSCRIPT_MERGE` | <= 30s |
| `RAG_INDEXING` | <= 120s |
| `SUMMARY` / LLM 相关 | <= 60s，由 Java llm-gateway 审计调用 |

## 10. 验收标准

1. 能消费 `MEETING_FULL_PIPELINE` 任务。
2. 能读取 TOS 音频并写入中间 artifact。
3. 能输出 channel map、质量报告、ASR 原始结果、diarization turns 和结构化转录。
4. 能生成 speaker candidates 且不泄露 embedding。
5. 能按 step 回写状态、进度和错误码。
6. 同一 callback 重放不产生重复业务结果。
7. Worker 异常退出后，Java lease 过期可重新入队，旧 attempt 结果不能覆盖新 attempt。
8. 所有模型产物绑定 artifact manifest。
9. 模型状态、workflow 状态和 health 能通过内部接口查看。
10. `ALIGNMENT`、`RAG_INDEXING`、`SPEAKER_MATCHING` 可降级时通过 `/complete phase=WORKER_DAG status=PARTIAL_SUCCEEDED + skippedSteps` 上报 worker phase partial；`DIARIZATION` 失败必须 `/fail`。
11. task message `pipelineSteps` 不得包含 `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION` / `EXPORT`；收到非法 step 时必须 fail fast 并上报 `INVALID_TASK_MESSAGE`。
