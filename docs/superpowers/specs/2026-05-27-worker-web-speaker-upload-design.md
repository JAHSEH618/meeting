# ai-worker-web：新人声纹录入 + 音频上传一路跑到底

**日期**：2026-05-27
**作者**：brainstorming session（用户 + Claude）
**状态**：Draft → 等执行
**前置约束**：Mac 开发阶段；ai-worker-web 作为全能 UI 替代 meeting-web；Java 仍是业务源头。

---

## 1. 背景与动机

当前 ai-worker-web 已落地"工作站向导"（todo-final.md §5），具备会议向导、声纹录入、术语、文档关联、speakers 认定、finalize、docx 导出全链路骨架，但是两个体验断点：

1. **声纹录入必须先有 Java person**：`EnrollmentPage` 要求从 Java `/api/persons` 搜索选中已有 person 才能起 session。新人无法从工作站现场录入。
2. **音频上传只有占位文案**：`MeetingWorkstationPage` 的 AUDIO 步骤里只显示 `POST /api/meetings/{id}/files/audio/uploads`，让用户自己 curl。没有真正的上传 UI。

后续 ai-worker 会迁到 N 卡机器，meeting-web 才是终端用户入口。**当前 Mac 阶段 ai-worker-web 需要在保持"数据写到 Java DB"的前提下，能完成上述两条流程**。

---

## 2. 决策锚点（不再改动）

| 决策 | 选择 | 理由 |
|---|---|---|
| **D1 — 数据归属** | 仍写 Java DB（保持 CLAUDE.md "Java 是业务源头"不变量） | 后期上 N 卡时只需切前端入口，无需迁数据 |
| **D2 — 新建 person UX** | 搜索结果区下方新增 "+ 新建人员"按钮 → modal 填 displayName/email → 调 Java 新端口 → 回填 personId 继续录声纹 | 改动最小、与现有 4 步会话编排兼容 |
| **D3 — 上传范围** | 音频（必填）+ 参考文档（可挑已有 / 可新上传 PDF·docx·ppt） | 与现有 MeetingDocument REFERENCE 角色对齐 |
| **D4 — 启动时机** | 音频 complete 后 Java 自动派 MQ，`holdAtWorkerPhase=false`，**一路跑到底**（含 DashScope 云端 SUMMARY/EXTRACTION） | 用户不希望多一次手动 finalize |
| **D5 — 说话人** | ai-worker 输出 candidates 不动；Java `WorkerPhaseCompletedListener` 在派 SUMMARY 前按阈值 **0.85** 自动 confirm；低于阈值保留 SPEAKER_xx label | 权限/事务由 Java 收口，本机 worker 不写业务 |
| **D6 — LLM** | 维持云端 DashScope（qwen-plus）。`CONFIDENTIAL`/`SECRET` fail-closed 不变 | 本期不引入本地 LLM |
| **D7 — 页面结构** | 3 个独立页面：`/enrollment` / `/meetings/new` / `/meetings/:id`；删除原 wizard | 一路跑到底后向导无意义 |
| **D8 — 实现路径** | BFF 细粒度透传（路径 A），前端做编排 | 端口语义单一，易于失败重试与归因 |
| **D9 — 参考文档上传时机** | 拖入立即上传 | 上传期可同步选音频，错误就地反馈 |
| **D10 — generic file upload** | Java 新增 `POST /api/files`（多分片协议），文档新上传走它 | 现有只有 meeting-scoped 音频上传，无法承载 reference 文档 |

---

## 3. 架构

```
┌────────────────────────┐    ┌──────────────────────┐    ┌────────────────────────┐
│ ai-worker-web (Mac)    │    │ ai-worker /admin BFF │    │ Java meeting-api       │
│ 3 pages:               │ →  │ (httpx 透传, JWT 透)  │ →  │ 业务源头 / DB / MQ     │
│  • /enrollment         │    │                       │    │  + POST /api/persons   │
│  • /meetings/new       │    │  新增 endpoint:        │    │    (NEW)                │
│  • /meetings/:id       │    │   POST /admin/persons │    │  + POST /api/files     │
└────────────────────────┘    │   POST /admin/files/* │    │    (NEW)                │
                              │   POST /admin/.../    │    │  + 既有上传/文档/任务   │
                              │       audio-upload     │    └─────────┬──────────────┘
                              │   POST /admin/.../docs │              │ MQ
                              └──────────────────────┘              ▼
                                                          ┌────────────────────────┐
                                                          │ ai-worker (Mac, 本机)   │
                                                          │ RabbitMQ consumer       │
                                                          │ ASR/diar/spk/embed/RAG  │
                                                          │ → callback → Java       │
                                                          └─────────┬──────────────┘
                                                                    │ SUMMARY/EXTRACTION
                                                                    ▼
                                                                 DashScope (云端)
```

### 数据流（声纹新人录入）

```
1. EnrollmentPage 搜索 "李四" 无结果 → 点 "+ 新建人员" → modal 填 displayName
2. 前端 → BFF POST /admin/persons → Java POST /api/persons → 返回 personId
3. 前端拿到 personId → 自动选中 → 走既有 4 步 (createSession → upload → preview → commit)
4. commit 内部 BFF 仍调 Java 三步 (profile → audio:upload → enrollments)
```

### 数据流（新会议一路跑到底）

```
1. NewMeetingPage 表单一次性收集：标题/安全级/术语/参考文档/音频
2. 拖入 PDF → 前端立刻调 BFF generic upload → 完成后调 POST /admin/documents → attach REFERENCE
3. 点 "开始处理" → 前端 orchestration:
   a. createMeeting
   b. PATCH glossary (如有术语)
   c. attach 已有文档 (如有)
   d. audio multipart upload init/parts/complete
      (Java 内部 complete 时自动创建 ProcessingTask hold=false 派 MQ)
4. navigate(/meetings/:id) — 用户在详情页看 SSE 进度
5. ai-worker 消费 MQ → ASR/diar/spk/embed/RAG → callback → Java
6. Java WorkerPhaseCompletedListener:
   a. 调 SpeakerAutoConfirmService.autoConfirmAboveThreshold(taskId)
      — 阈值 0.85，自动写 confirmation (source=AUTO_CONFIRM)
   b. javaLlmPhaseOrchestrator.run() — SUMMARY → EXTRACTION → TERMINAL
7. 前端 SSE 看到 TERMINAL → 展示纪要 + 说话人结果 + 导出 docx
```

---

## 4. 契约（packages/meeting-contracts/）

### 4.1 openapi/public-api.yaml

新增端点：

- **`POST /api/persons`** —— 创建 person
  - body: `{displayName: string, email?: string, externalId?: string}`
  - response: `{personId, displayName, email?, externalId?, createdAt}`
  - error codes: `PERSON_DISPLAY_NAME_REQUIRED` / `PERSON_DUPLICATE`（dedup 策略详见 §5.3）

- **`POST /api/files`** —— generic 多分片上传 init
  - body: `{fileName, contentType, fileSizeBytes, fileSha256, partSizeBytes?}`
  - response: `{uploadId, parts: [{partNumber, presignedUrl, ...}]}`（仿 audio uploads 协议）

- **`POST /api/files/{uploadId}/parts`** —— 申请新 part presign URL
- **`POST /api/files/{uploadId}/complete`** —— 完成上传，返回 `{fileId, sha256, sizeBytes}`
- **`POST /api/files/{uploadId}/abort`** —— 中止

### 4.2 schemas/common/error-codes.yaml

新增：
- `PERSON_DISPLAY_NAME_REQUIRED`（4xx）
- `PERSON_DUPLICATE`（4xx，retryable=false，details 含已存在的 personId）
- `FILE_UPLOAD_NOT_FOUND`（4xx）
- `FILE_MIME_NOT_ALLOWED`（415）

### 4.3 校验门
- `npm run check` 通过
- `npm run codegen` 后所有语言生成产物 `git diff` 干净
- `npm run codegen:check-temp` 0 diff

---

## 5. Java meeting-api 改动

### 5.1 模块映射

| 模块 | 文件 | 改动 |
|---|---|---|
| **client** | `client/person/PersonFacade.java`, `CreatePersonCommand`, `PersonDTO` | 新建 |
| **client** | `client/storage/GenericFileFacade.java`, `CreateFileUploadCommand`, `CompleteFileUploadCommand` 等 | 新建 |
| **adapter** | `adapter/person/PersonController.java` | 新建 `POST /api/persons` |
| **adapter** | `adapter/storage/FileUploadController.java` | 新建 generic multipart 4 个端点 |
| **app** | `app/person/PersonApplicationService.java` | 写 `persons` 表 + outbox `PersonCreatedEvent` + dedup 策略（见 §5.3） |
| **app** | `app/storage/GenericFileUploadApplicationService.java` | 仿 `AudioUploadApplicationService`，落 TOS/MinIO，租户路径隔离 |
| **app** | `app/task/WorkerPhaseCompletedListener.java` | **修改**：`MEETING_FULL_PIPELINE` 分支、`hold=false` 时，**先**调 `SpeakerAutoConfirmService.autoConfirmAboveThreshold(taskId)`，**再** `javaLlmPhaseOrchestrator.run(...)` |
| **app** | `app/speaker/SpeakerAutoConfirmService.java` | **新建**：扫该 task 的 meeting 下所有 SpeakerCandidate；按阈值 0.85 取 top-1 confidence；复用 `MeetingSpeakerApplicationService.confirmSpeaker(...)`，源置 `AUTO_CONFIRM`；写 outbox `SpeakerAutoConfirmedEvent` |
| **infra** | `infrastructure/persistence/JdbcPersonRepository.java`, `JdbcGenericFileRepository.java` | 新建 |
| **infra** | Flyway `V202605270001__person_dedup_unique.sql` | 详见 §5.3 |
| **test** | `PersonApplicationServiceTest`、`SpeakerAutoConfirmServiceTest`、`WorkerPhaseCompletedListenerTest`（扩展）、`PersonControllerIT`、`FileUploadControllerIT` | |

### 5.2 SpeakerAutoConfirmService 详细规范

- 入参：`tenantId`, `taskId`
- 流程：
  1. `processingTaskRepository.findById(tenantId, taskId)` → 拿到 meetingId
  2. `meetingSpeakerRepository.listByMeeting(tenantId, meetingId)` → 所有 speaker labels + candidates
  3. for each label:
     - 取 candidates 按 confidence 降序排
     - 若 top-1.confidence ≥ `AUTO_CONFIRM_THRESHOLD`（常量 0.85），且当前 verificationStatus 仍是 `UNCONFIRMED`：
       - 调 `meetingSpeakerApplicationService.confirmSpeaker(meetingId, label, top1.personId, source=AUTO_CONFIRM)`
       - 写 audit log: `auto_confirm taskId=... label=... personId=... confidence=...`
- 异常：不抛到上层（沿用 Listener 的 `RuntimeException` 捕获语义）
- 不变量：confirm 失败的 label 维持 SPEAKER_xx，让 SUMMARY 用 label 渲染

### 5.3 Person dedup 策略

按 `(tenantId, displayName)` **不**强制 unique（同名是真实情况）。dedup 策略：
- `POST /api/persons` 收到请求时，先按 `displayName` exact match 查现有 person
  - 如果 ≥ 1 个匹配 → 返回 409 `PERSON_DUPLICATE`，details 含所有匹配 `[{personId, displayName, email}]`
  - 前端 modal 收到 409 时显示 "已存在以下同名人员，是否使用？" + 列表 + "仍创建新的"按钮
  - 前端再发请求带 `forceCreate=true` 时跳过 dedup
- 不加 unique 约束（避免 DB 层面拒绝合理的同名场景）

Flyway `V202605270001__person_dedup_unique.sql`：仅补 `CREATE INDEX IF NOT EXISTS idx_persons_tenant_displayname ON persons (tenant_id, display_name)` 以支持 exact match 查询。

### 5.4 不变量与红线

- Java 仍是唯一权限/写入方
- 所有新端口走 Java 现有 JWT + RLS（必须 set `app.tenant_id`）
- `POST /api/files` 校验 MIME 白名单：`application/pdf`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/vnd.openxmlformats-officedocument.presentationml.presentation`, `text/plain`, `text/markdown`；其他 → 415 `FILE_MIME_NOT_ALLOWED`
- ArchUnit：现有规则覆盖（person/storage 都在已有 COLA 层下），无需改

---

## 6. ai-worker BFF 改动（apps/ai-worker/ai_worker/admin/）

| 文件 | 改动 |
|---|---|
| `admin/persons.py` | **新建** router：`POST /admin/persons` 透传 Java；`GET /admin/persons?q=` 已有继续 |
| `admin/files.py` | **新建** router：`POST /admin/files/uploads`、`POST /admin/files/uploads/{id}/parts`、`/complete`、`/abort` 逐一透传 |
| `admin/enrollment.py` | **修复**：line 141 路径 `POST /api/speakers/profiles` → `POST /api/speaker-profiles`（与 Java SpeakerProfileController 对齐）；body 增 `displayName`/`consentSource`/`consentVersion`；同步 line 150 `audio:upload` 路径为实际存在的 Java 路径（或新增 Java 端点） |
| `admin/meetings.py` | **删 C3.7 `:start-processing`** 透传（Java 在 audio upload complete 时已经自动创建 ProcessingTask `hold=false` 派 MQ，前端不再需要显式触发） |
| `admin/meetings.py` | **删 C3.9 `:finalize`** 与 `resume-java-phase`（一路跑到底不需要） |
| `admin/router.py` | 挂新 router：`persons`、`files` |
| `tests/admin/test_persons.py` | **新建**：透传 / JWT / 错误传播 / 409 dedup |
| `tests/admin/test_files.py` | **新建**：多分片透传 4 个端点 |
| `tests/admin/test_enrollment_session.py` | **改**：commit 三步 mock URL + body 更新 |
| `tests/admin/test_meeting_orchestration.py` | **改**：删 hold=true 断言 / 删 finalize 链路 |

BFF 仍为细粒度透传，不做任何 orchestration（路径 A 设计原则）。

---

## 7. ai-worker-web 前端改动（apps/ai-worker-web/src/）

### 7.1 路由

```
/             → 重定向 /meetings
/login        → 已有
/enrollment   → EnrollmentPage（已有，改造）
/meetings     → MeetingsListPage（已有，保留）
/meetings/new → NewMeetingPage（新建）
/meetings/:id → MeetingDetailPage（新建）
```

**删除**：`MeetingWorkstationPage.tsx`、`pages/workstation/WizardRail.tsx`、`pages/workstation/WorkstationShell.tsx`、`features/wizard/`（整目录：`useWizard.ts`、`WizardContext.tsx`、`Stepper.tsx`、`useWizard.test.ts`）

### 7.2 EnrollmentPage 改造

- 搜索结果区下方加 "+ 新建人员" 按钮
- 点击 → 弹 modal（同进程组件，不用 portal）：
  - 字段：`displayName*`, `email?`
  - 提交 → `POST /admin/persons`
    - 200 → 拿 personId → 自动 setPersonId → 关闭 modal
    - 409 `PERSON_DUPLICATE` → modal 内列出现有同名人员 → 用户选择"使用已有"（取该 personId 关闭 modal）/ "仍创建新的"（带 `forceCreate=true` 重发）
- 后续 4 步流程不变

### 7.3 NewMeetingPage（新建）

单页布局，**一次性收集**：
- 标题 `*`（input）
- 安全级 `*`（select：PUBLIC/INTERNAL/CONFIDENTIAL/SECRET，默认 INTERNAL）
- 术语 chips（沿用现有 GLOSSARY chip 逻辑：Enter 添加、× 删除、≤200 条、单 term ≤64 字符）
- 参考文档区：
  - 子区 a：搜索已有（沿用 `searchDocuments` debounced 搜索 + attach 按钮）
  - 子区 b：dropzone（accept=`.pdf,.docx,.pptx,.txt,.md`）—— **拖入立即上传**：
    1. `MultipartUploader.upload(file)` → BFF generic upload → 拿 fileId
    2. `POST /admin/documents` 注册（title=file.name, fileId, documentType 由后缀推导, securityLevel=继承 meeting 当前选项, contentHash=sha256）
    3. 上传过程中显示 progress bar 与可"取消"按钮（cancel 调 abort endpoint）
    4. 成功后该文档自动 attach 为 REFERENCE（前端缓存待 meeting 创建后批量 attach）
- 音频 dropzone `*`（accept=`audio/*`）—— **不立即上传**：仅记录 File 对象到 state
- "开始处理"按钮

按钮点击 → 前端串行 orchestration：
```typescript
async function startProcessing() {
  // 1. createMeeting
  const meeting = await createMeeting({title, securityLevel, language: 'zh', participants: []});

  // 2. PATCH glossary
  if (terms.length > 0) await updateMeetingGlossary(meeting.meetingId, terms);

  // 3. attach all reference docs (已上传的新文档 + 已选已有文档)
  for (const docId of allRefDocIds) {
    await attachMeetingDocument(meeting.meetingId, {documentId: docId, role: 'REFERENCE'});
  }

  // 4. audio upload (only now)
  const uploader = new MultipartUploader(meeting.meetingId, audioFile);
  await uploader.upload();  // init → parts (progress) → complete
  // Java 内部 complete 时自动派 MQ (hold=false)

  // 5. navigate
  navigate(`/meetings/${meeting.meetingId}`);
}
```

任何一步失败 → 显示错误 + "重试该步"按钮（前端记录已完成步骤 id），不重头来。

### 7.4 MeetingDetailPage（新建）

- **顶部**：meeting 元信息 + 当前 task 的 phase / status pill
- **中部**：SSE 进度面板
  - 先 `GET /api/meetings/{id}` 拿到 latest task 的 taskId
  - 再订阅 SSE 端点 `/api/processing-tasks/{taskId}/events`
  - 渲染 step grid：AUDIO_PREPROCESS / ASR / ALIGNMENT / DIARIZATION / SPEAKER_EMBEDDING / SPEAKER_MATCHING / TRANSCRIPT_MERGE / RAG_INDEXING / SUMMARY / EXTRACTION
  - 每步显示 progress % + 状态色（pending/running/succeeded/failed）
- **下部**（task TERMINAL 后才显示）：
  - 说话人结果：每个 label → 自动认定的真人姓名（带"自动认定"badge）或保留 SPEAKER_xx
  - 纪要（`SafeMarkdown` 复用）
  - 导出 docx 按钮（沿用现有 createExport / pollExport 逻辑）
- 状态变 `SUCCEEDED` / `PARTIAL_SUCCEEDED` / `FAILED` 时 UI 反馈：
  - `PARTIAL_SUCCEEDED` 时显示哪一步失败 + 是否可重试
  - 安全级阻断时（`SECURITY_LEVEL_BLOCKED`）显著警示

### 7.5 共享基础设施

- **新建** `src/shared/upload/MultipartUploader.ts`：
  - 封装 init → parts → complete 状态机
  - 入参：file, mode ('audio' | 'generic'), meetingId?（mode=audio 必填）, callbacks {onProgress, onError}
  - 提供 `abort()` 调用 BFF abort endpoint
  - 单 part 失败重试 3 次指数退避
- **新建** `src/shared/components/PersonCreateModal.tsx`
- **改** `src/shared/api/endpoints.ts`：新增 `createPerson`, `createDocumentFromFile`（上传 + 注册）, `attachMeetingDocument`（已有但前端逻辑迁出）；**删除** `finalizeMeeting`, `startMeetingProcessing`（不再调用）
- **改** `src/shared/api/types.ts`：补 `PersonDTO`, `CreatePersonRequest`, `FileUploadSessionDTO`, `CreateDocumentRequest`
- **改** `src/App.tsx`：路由替换为新结构，删除 wizard 相关 import

### 7.6 测试

- **Vitest**：
  - `MultipartUploader.test.ts`（happy / abort / 单 part fail 重试 / 全失败）
  - `PersonCreateModal.test.tsx`（提交 / 409 dedup 展示已有 / forceCreate）
  - `NewMeetingPage.test.tsx`（全链路 mock，含 doc 上传 happy / audio fail 重试）
  - `MeetingDetailPage.test.tsx`（SSE mock，TERMINAL 后纪要展示）
  - `EnrollmentPage.test.tsx`（+ 新人 modal 链路）
- **Playwright e2e**：
  - `enrollment-new-person.spec.ts`（新人 happy path）
  - `new-meeting-end-to-end.spec.ts`（建会议 + 上传音频 + 等到 docx，用 MSW 加速 SSE）

### 7.7 性能预算

- 首屏 JS gzip < 200KB（沿用现有 budget）
- `MeetingDetailPage` 与 `NewMeetingPage` 用 React lazy 拆 chunk
- 音频上传 part size 默认 5MB，最多并行 3 个 part

---

## 8. 错误处理矩阵

| 场景 | 行为 |
|---|---|
| `POST /admin/persons` 重名 | 409 `PERSON_DUPLICATE` + 已存在 personIds；前端 modal 提示选择已有或强制创建 |
| 文件上传单 part 失败 | `MultipartUploader` 内重试 3 次指数退避；终失败 → UI"该文档上传失败，删除该项或重试" |
| 音频 complete 后 Java 派 MQ 失败 | Java 返回 5xx；前端 NewMeetingPage 报错 + "重试上传"按钮（**复用 uploadId** 不重新分片） |
| `SpeakerAutoConfirmService` 异常 | 不阻塞 Java LLM phase；详情页"自动认定失败，全部保留 label" |
| DashScope 超时/限流 | Java `SUMMARY` step 标 `FAILED` → task `PARTIAL_SUCCEEDED`；详情页"纪要生成失败，可重试"；**不挡 docx 导出** |
| 安全级 CONFIDENTIAL/SECRET 触发 LLM | `SECURITY_LEVEL_BLOCKED` → task `PARTIAL_SUCCEEDED`；详情页明显警示 |
| SSE 断流 | 前端自动重连 3 次 → 回落到 5s 轮询 `GET /api/processing-tasks/{taskId}` |

---

## 9. 阶段化交付

| Phase | 内容 | 完成判据 |
|---|---|---|
| **P1 契约** | §4 全部 + codegen 全语言对齐 | `npm run check` 绿；`git diff` 干净 |
| **P2 Java 后端** | §5 全部 | `./mvnw verify` 绿（含 PersonControllerIT / FileUploadControllerIT / SpeakerAutoConfirmServiceTest） |
| **P3 BFF** | §6 全部 | `uv run pyright + pytest` 绿 |
| **P4 前端** | §7 全部 | Vitest + Playwright 绿；`npm run build` gzip < 200KB |
| **P5 联调 + 文档** | docker-compose 起全链路 happy path；更新 todo-final.md / ai-worker-web SPEC.md | 手测通过 + 截图归档 |

**推荐顺序**：P1 阻塞 P2/P3/P4；P2/P3/P4 可并行；P5 最后。

---

## 10. 验收标准（端到端 happy path）

### 10.1 声纹新人录入

1. 登录 → 进 `/enrollment`
2. 搜索框输 "李四" 无结果
3. 点 "+ 新建人员" → modal 填 displayName="李四" → 提交
4. modal 关闭，"李四" 自动选中
5. 上传 5 秒音频 → 点 "上传并预览" → quality_score ≥ 0.5
6. 点 "确认录入" → state=COMMITTED
7. 验证 Java DB：`persons` 表多一行 displayName=李四；`speaker_profiles` 表多一行关联 personId；`speaker_enrollments` 表多一行

### 10.2 新会议一路跑到底

1. 登录 → 进 `/meetings/new`
2. 填标题="季度评审" + 安全级=INTERNAL + 加术语 ["LLM", "DAG"] + 拖一个 PDF（上传 progress 跑完）+ 拖一个 MP3（≤ 50MB）
3. 点 "开始处理" → 自动 navigate `/meetings/:id`
4. 看到 SSE 步骤逐个变绿（AUDIO_PREPROCESS → … → RAG_INDEXING → SUMMARY → EXTRACTION）
5. 最后纪要 markdown 出现
6. 说话人结果显示 "李四（自动认定）" 或 "SPEAKER_01（未认定）"
7. 点导出 docx → 浏览器下载 .docx 文件

---

## 11. 风险闭环

| Risk | 说明 | 缓解 |
|---|---|---|
| R1 — Person dedup 误判 | 同名人员被强制 dedup 阻塞合理新增 | dedup 仅 409 提示，不强 unique；前端提供 `forceCreate=true` |
| R2 — generic file upload TOS 路径冲突 | `POST /api/files` 与 audio 共用桶但路径前缀不同 | 路径 `tenants/{tenantId}/generic-files/{uploadId}/{partNumber}` 与 audio 隔离 |
| R3 — AutoConfirm 误认定 | 阈值 0.85 可能仍误标 | source=AUTO_CONFIRM 标记 + 详情页可重选；后期可调阈值（当前写常量） |
| R4 — 上传期切页面 | 用户在 NewMeetingPage 拖完 PDF 后立刻关页 | MultipartUploader 用 AbortController；离开页时自动 abort 已上传分片 |
| R5 — 多文件并发上传抢带宽 | 同时拖多个 PDF + 音频 | 上传并发上限 = 2（PDF 一个 + 音频一个），其余排队 |
| R6 — DashScope 超时长 | qwen-plus 长文本可能 30s+ | SSE step heartbeat 已存在；前端 30s 内无变化时显示"仍在处理…" |
| R7 — SSE 在 Mac 网络抖动断流 | 用户白屏 | 5s 轮询 fallback |

---

## 12. 范围外（YAGNI）

显式不在本期：
- **本地 LLM**（Ollama / vLLM）：维持 DashScope
- **Person 编辑 / 删除**：本期只 POST；后续手动改 DB 或加端点
- **Document 编辑**：复用现有 DocumentController（list/get/delete/reindex 已存在）
- **Audit log UI**：本期 AutoConfirm 仅写日志，不做 UI
- **离线模式**：用户已确认走 Java 在线
- **批量声纹录入**：单次单文件
- **Person 的真实 user / 邮箱去重**：本期仅 displayName 软 dedup
