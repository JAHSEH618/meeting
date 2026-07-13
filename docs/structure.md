# 本地会议智能系统架构图

基于 `本地会议智能系统技术方案文档-优化版.md` 梳理。核心边界是：Java 使用 Spring Boot + 阿里 COLA-V5 负责业务事实、权限、任务状态、审计和对外 API；Python 负责 AI Pipeline、音频处理和模型调用；所有中间产物进入 PostgreSQL 或 TOS，保证任务可重试、可回放。

```mermaid
flowchart LR
    User["用户 / 管理员"]

    subgraph Client["前端层"]
        Web["meeting-web<br/>会议列表 / 上传 / 进度 / 转录修正 / 纪要 / RAG 问答"]
        AdminWeb["ai-worker-web<br/>运维工作台 SPA（由 ai-worker 挂载于 /workstation/）"]
    end

    subgraph Java["业务层: meeting-api Spring Boot + COLA-V5 模块化单体"]
        BFF["api / BFF<br/>入口 / 鉴权 / 参数校验 / 限流 / 响应聚合"]
        Auth["user-auth<br/>用户 / 组织 / 角色 / 权限"]
        Meeting["meeting<br/>会议生命周期 / 转录 / 纪要 / 事项 / 导出"]
        Task["task<br/>处理任务 / 状态机 / 步骤 / 重试 / 取消 / 幂等"]
        Storage["storage<br/>TOS 元信息 / 分片上传 / 签名 URL / 生命周期"]
        LLMG["llm-gateway<br/>模型路由 / 数据边界 / Prompt / 结构化输出 / fallback"]
        Audit["audit<br/>处理 / 查看 / 导出 / 权限审计"]
        Speaker["speaker<br/>声纹档案 / 授权 / 匹配确认 / 删除"]
        RAG["rag<br/>权限过滤 / 检索编排 / citation 组装 / 问答入口"]
        Document["document<br/>文档上传 / 解析 / 知识入库"]
        Export["export<br/>[一期] 异步导出 / 短链撤销 / 快照版本"]
        JavaLLM["java-llm 阶段<br/>SUMMARY / EXTRACTION（TaskStepProgressService / JavaLlmPhaseOrchestrator）"]
    end

    subgraph Infra["数据与基础设施层"]
        DB[("PostgreSQL + pgvector<br/>业务数据 / 任务状态 / 审计 / MVP 向量检索")]
        MQ["RabbitMQ<br/>异步任务队列（唯一 broker）"]
        TOS["火山引擎 TOS<br/>原始音频 / 转码音频 / 中间 JSON / 导出文件"]
        Obs["Prometheus / Grafana / Logs / Trace<br/>可观测性与告警"]
    end

    subgraph Queue["资源隔离队列"]
        QAudio["audio-cpu-queue<br/>ffmpeg / VAD / 质量检测"]
        QAsr["gpu-asr-queue<br/>Qwen3-ASR"]
        QAlign["gpu-align-queue<br/>[后续独立队列] Forced Alignment"]
        QDiar["gpu-diar-queue<br/>Diarization"]
        QSpeaker["gpu-speaker-queue<br/>Speaker Embedding"]
        QEmbed["embed-queue<br/>Text Embedding"]
        QRerank["rerank-queue<br/>[后续独立队列] Reranker"]
        QLLM["llm-queue<br/>[拓扑保留] 无 Python 消费者，SUMMARY / EXTRACTION 由 Java 进程内触发"]
        QExport["export-queue<br/>[一期] Markdown / DOCX / PDF"]
    end

    subgraph Python["计算层: ai-worker"]
        Worker["ai-worker 应用<br/>Clean Architecture"]
        FastAPI["FastAPI<br/>内部管理 / health / rerank / workflow control"]
        AdminBFF["Admin BFF（ai_worker/admin/）<br/>/admin/* + /api/* 透传 · JWT aud=ai-worker-admin"]
        WorkerRunner["RabbitMQ 消费者（pika）<br/>队列消费 / step 执行"]
        Workflow["进程内 Pipeline DAG（ai_worker/pipeline/）<br/>retry / cancel / resume"]
        Runtime["model-runtime<br/>MVP 为 ai-worker 进程内 Python package"]

        Transcode["音频标准化<br/>保留 channel_map / 按需 mono"]
        Quality["质量检测"]
        VAD["VAD 区间 / ASR chunk<br/>30-120s overlap 0.3-0.8s"]
        ASR["ASR 转写"]
        Align["时间戳对齐<br/>按需 Forced Alignment"]
        Diar["说话人分离"]
        Merge["ASR + Diarization 合并<br/>结构化转录"]
        SpeakerRec["声纹识别<br/>候选匹配"]
        Indexing["RAG 切块与入库<br/>embedding / rerank metadata"]
    end

    subgraph Model["外部或私有模型层"]
        ThirdLLM["第三方 LLM API<br/>OpenAI-compatible"]
        LocalLLM["Local vLLM / SGLang / Ollama<br/>[预留/后续] 高敏或私有化场景"]
        AudioModels["本地音频模型<br/>Qwen3-ASR / Diarization / Speaker Embedding"]
        RagModels["检索模型<br/>Text Embedding / Reranker"]
        VectorDB["Qdrant / Milvus<br/>[预留/后续] 生产可选向量库"]
    end

    User --> Web
    User --> AdminWeb
    Web --> BFF
    AdminWeb --> AdminBFF
    AdminBFF -- "/api/* 透传到公开 API" --> BFF

    BFF --> Auth
    BFF --> Meeting
    BFF --> Task
    BFF --> Storage
    BFF --> LLMG
    BFF --> Speaker
    BFF --> RAG
    BFF --> Document
    BFF --> Export

    Auth --> DB
    Meeting --> DB
    Task --> DB
    Storage --> DB
    LLMG --> DB
    Audit --> DB
    Speaker --> DB
    RAG --> DB
    Document --> DB
    Export --> DB

    Storage --> TOS
    Export --> TOS
    Meeting --> Task
    Task -- "发布异步任务" --> MQ
    MQ --> QAudio
    MQ --> QAsr
    MQ --> QDiar
    MQ --> QSpeaker
    MQ --> QEmbed
    MQ --> QLLM
    MQ --> QExport
    MQ -. "后续启用" .-> QAlign
    MQ -. "后续启用" .-> QRerank

    QAudio --> WorkerRunner
    QAsr --> WorkerRunner
    QDiar --> WorkerRunner
    QSpeaker --> WorkerRunner
    QEmbed --> WorkerRunner
    QExport --> Export
    QAlign -. "后续独立消费" .-> WorkerRunner
    QRerank -. "后续独立消费" .-> WorkerRunner

    Worker --> FastAPI
    Worker --> AdminBFF
    Worker --> WorkerRunner
    Worker --> Workflow
    WorkerRunner --> Workflow
    FastAPI --> Workflow
    Worker --> TOS
    Worker --> Runtime
    Runtime --> AudioModels
    Runtime --> RagModels

    Workflow --> Transcode
    Transcode --> Quality
    Transcode --> VAD
    VAD --> ASR
    ASR --> Align
    Transcode --> Diar
    Align --> Merge
    Diar --> Merge
    Merge --> SpeakerRec
    Merge --> Indexing

    Worker -- "internal callback API<br/>HMAC / 幂等键 / trace_id" --> Task
    Worker -- "回写结构化结果" --> Meeting
    Worker -- "声纹候选" --> Speaker
    Worker -- "知识切块状态" --> RAG

    LLMG --> ThirdLLM
    LLMG -. "预留" .-> LocalLLM
    Task -- "WORKER_DAG_DONE 事件<br/>outbox · Java 进程内消费" --> JavaLLM
    JavaLLM -- "通过 llm-gateway 调用" --> LLMG
    RAG -- "权限范围实时计算" --> DB
    RAG -- "候选召回" --> DB
    RAG -- "已授权候选 rerank<br/>internal HMAC" --> FastAPI
    RAG -. "生产可切换" .-> VectorDB
    Indexing --> DB
    Indexing -. "生产可写入" .-> VectorDB

    Java --> Obs
    Python --> Obs
    MQ --> Obs
    DB --> Obs

    classDef frontend fill:#e8f3ff,stroke:#3b82f6,color:#111827;
    classDef service fill:#eefdf4,stroke:#16a34a,color:#111827;
    classDef infra fill:#fff7ed,stroke:#f97316,color:#111827;
    classDef compute fill:#f5f3ff,stroke:#7c3aed,color:#111827;
    classDef model fill:#fef2f2,stroke:#ef4444,color:#111827;

    class Web,AdminWeb frontend;
    class BFF,Auth,Meeting,Task,Storage,LLMG,Audit,Speaker,RAG,Document,Export,JavaLLM service;
    class DB,MQ,TOS,Obs,QAudio,QAsr,QAlign,QDiar,QSpeaker,QEmbed,QRerank,QLLM,QExport infra;
    class Worker,FastAPI,AdminBFF,WorkerRunner,Workflow,Runtime,Transcode,Quality,VAD,ASR,Align,Diar,Merge,SpeakerRec,Indexing compute;
    class ThirdLLM,LocalLLM,AudioModels,RagModels,VectorDB model;
```

## 架构要点

1. MVP 后端采用一个 Spring Boot + 阿里 COLA-V5 模块化单体，不提前拆成多个 Java 微服务；工程按 client / adapter / app / domain / infrastructure / start 分层，分层内按业务域隔离。
2. `meeting-api` 是业务事实来源，`ai-worker` 只负责计算执行，不直接写业务库，也不自行判断用户权限。
3. Java 和 Python 通过队列、TOS URI、结构化 JSON 和 internal callback API 交互。
4. Python 侧采用 FastAPI + Clean Architecture + 基于 pika 的 RabbitMQ 消费者 + 进程内 pipeline DAG（`apps/ai-worker/ai_worker/pipeline/`），RabbitMQ 是唯一 broker；`model-runtime` 在 MVP 是 `ai-worker` 内部包，只有当模型需要独立扩容、依赖隔离或显存隔离时才拆为 HTTP / gRPC 服务。
5. RAG 权限必须由 Java 实时计算，向量库只做候选召回，不能作为权限事实来源。
6. LLM 调用统一经过 `llm-gateway`，集中处理模型路由、数据边界策略、Prompt 版本、结构化输出、fallback 和审计；一期转写文本发送第三方 LLM 前不做脱敏。
7. Pipeline 以进程内 DAG 形式在 `ai-worker` 内执行（`ai_worker/pipeline/`），并按 CPU、ASR、分人、声纹、embedding 拆 RabbitMQ 队列做资源隔离；Forced Alignment 和 Rerank 一期在 `ai-worker` 进程内按需执行或 lazy-load。
8. 一期默认启用 DashScope、pgvector、audio-cpu / gpu-asr / gpu-diar / gpu-speaker / embed / llm / export 队列；不创建 `gpu-align-queue` 和 `rerank-queue`。`llm-queue` 仅在拓扑中保留、无 Python 消费者：`SUMMARY` / `EXTRACTION` 由 Java 进程内消费 `WORKER_DAG_DONE` 事件触发（`TaskStepProgressService` / `JavaLlmPhaseOrchestrator` 经 llm-gateway 调用），不经过 Python。
9. 一期预留但默认不启用：LocalLLM 用于后续高敏或私有化场景，Qdrant / Milvus 用于后续外置向量库，`gpu-align-queue` 用于后续 Forced Alignment 独立扩容，`rerank-queue` 用于后续独立 Rerank 扩容。（会议安全分级与 LLM 阻断门已在 Phase K 移除，会议不分级。）
10. 一期 `export-queue` 由 `meeting-api` Java 进程内的 `export` 模块消费，通过 LibreOffice headless 或等价组件生成 Markdown / DOCX / PDF；不进入 Python `WorkerRunner`。独立 export worker 仅作为后续资源隔离扩展。
11. 数据流向约束：所有 PostgreSQL 业务写操作都源自 `meeting-api`；`ai-worker` 不持有业务库凭证，不直接写 `knowledge_chunks`、`transcript_segments` 或任何声纹表，只能通过 internal callback API 回写结构化结果或 artifact URI。
12. 前端有两个 SPA：`meeting-web`（用户端，仅消费 meeting-api 公开 REST + SSE）和 `ai-worker-web`（运维工作台，由 ai-worker 挂载于 `/workstation/`）。后者采用双后端模式：`/admin/*` 走 ai-worker 的 Admin BFF（`apps/ai-worker/ai_worker/admin/`），`/api/*` 由其透传到 Java 公开 API，鉴权用 Java 签发、ai-worker 校验的 JWT（`aud=ai-worker-admin`）。
