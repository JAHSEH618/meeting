# Meeting Frontends Iceberg Refactor Design

> 推倒重做 2026-05-26 旧设计。新方向：冷调瑞典编辑风格 Iceberg + 把后端阶段模型搬到屏幕上 + TanStack Query/Zustand 把数据层补齐到 CLAUDE.md 既定栈。

## 范围

重构两个 React 前端，物理隔离、视觉同根：

- `apps/meeting-web`：业务控制台，18 个路由。
- `apps/ai-worker-web`：运营工作站，4 个路由。

后端不动业务逻辑、不改 schema，**只新增** `GET /admin/meetings`（admin BFF 列表 endpoint）。Public API、Public DTO、SSE 契约、callback 验证规则、KMS / TOS 边界全部保持。

## 产品模型

两个 app 共用 Iceberg 视觉根，但用语和密度不同。

`meeting-web` 是给业务用户看的控制台。它回答的是「这件事现在在系统里是什么状态」：会议处于哪个阶段、转录有没有被改导致下游 STALE、RAG 答案有没有引用覆盖、合规上有没有未释放保留或正在跑的删除任务、有多少 AI 建议待确认。语气面向结果、对业务事实负责。

`ai-worker-web` 是给运营人员看的工作站。它把人工控制的处理流水线写在脸上：建会议、挂术语 / 文档、启动 worker（hold）、确认说话人、resume Java phase、创建并轮询导出。语气面向操作、贴近后端 phase 模型。

## 视觉系统（Iceberg）

每个 app 在自己的 `app.css` / `styles.css` 顶部声明同一组 `:root` token。命名共享、物理隔离。

**表层**
- `--surface-base #f7f9fb` 页面背景
- `--surface-raised #ffffff` 卡片、表格、抽屉
- `--surface-sunken #eef2f6` 输入框、disabled 区

**墨色**
- `--ink-1 #0f172a` 主文 / 强标题
- `--ink-2 #475569` 次文
- `--ink-3 #64748b` 提示、副标
- `--ink-4 #94a3b8` 微弱

**描边**
- `--line-1 #dde3eb` 卡片、表格主分
- `--line-2 #edf1f5` 行间次分
- `--line-3 #f1f5f9` 隔行

**主调与状态**
- `--accent #1d4ed8` / `--accent-hover #1e40af` / `--accent-active #1e3a8a` / `--accent-soft #dbeafe`
- `--info` 同 accent
- `--success #0e7490` / `--success-soft #ecfeff` / `--success-ink #155e75`
- `--warn #b45309` / `--warn-soft #fef3c7` / `--warn-ink #92400e`
- `--danger #be123c` / `--danger-soft #fee2e2` / `--danger-ink #9f1239`

**焦点环**
- `--focus #1d4ed8`，全局 `:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }`

**半径与间距**
- 半径上限 8px：`--radius-s 4px` 内联控件 / `--radius-m 6px` 卡片按钮 / `--radius-l 8px` 大容器
- 间距阶梯：`--space-1 4` / `--space-2 8` / `--space-3 12` / `--space-4 16` / `--space-5 20` / `--space-6 24` / `--space-8 32` / `--space-10 40`

**字体**
- `--font-sans: Inter, "PingFang SC", "Microsoft YaHei", Arial, sans-serif`
- 主号 13px UI / 14px 正文 / 12px 表头 + 0.04em 字距 + 大写
- 数字列一律 `font-variant-numeric: tabular-nums`：进度、置信度、时间码、attempt、字节、版本号

**断点**
- < 768px：侧栏折叠为抽屉，wizard rail 折叠为顶部水平 stepper
- ≥ 768px 且 < 1280px：侧栏可见但宽度收为 200px（meeting-web）
- ≥ 1280px：完整 240px 侧栏

**动画**
- 过渡只允许 `transform / opacity / background-color / border-color / color / box-shadow`，禁 `transition: all`
- `@media (prefers-reduced-motion: reduce)` 关闭所有非必要过渡；RAG 高亮动画降级为 1 秒纯色填充

**语义化 class（两个 app 各自实现一份）**

shell：`.shell / .shell__rail / .shell__rail-section / .shell__rail-link / .shell__main / .crumbs / .skip-link`

页面：`.page-header / .page-title / .page-subtitle / .page-actions / .tabbar / .tab / .tab[aria-current="page"]`

容器：`.card / .stack / .grid / .split / .toolbar / .empty-state / .drawer / .drawer__handle`

表格：`.data-table / .data-table__row / .data-table__head / .data-table__num`

控件：`.button / .button--primary / .button--secondary / .button--ghost / .button--danger / .button--icon / .field / .field__label / .field__input / .field__hint / .field__error / .field[aria-invalid]`

标识：`.pill / .pill--info / .pill--success / .pill--warn / .pill--danger / .pill--neutral / .dot / .badge--soft`

状态：`.banner / .banner--info / .banner--warn / .banner--danger / .banner--success / .status-row / .metric / .metric__label / .metric__value`

进度：`.progress / .progress__fill / .phase-strip / .phase-strip__seg / .phase-strip__seg[data-state]`

ai-worker-web 额外：`.workstation / .workstation__rail / .workstation__canvas / .wizard / .wizard__group / .wizard__step / .wizard__step[data-state] / .wizard__backend-summary`

不引入跨 workspace UI 包。命名一致只为减少认知成本，物理隔离避免一改全炸。

## 信息架构

### meeting-web 外壳

桌面 ≥ 1280px：

```
┌─────────┬──────────────────────────────────────────────┐
│ 会议系统 │ 会议 › 季度复盘 2026Q1 › 转录                  │
│         ├──────────────────────────────────────────────┤
│ + 新建   │ 季度复盘 2026Q1            [上传音频] [创建任务]│
│   会议   │ INTERNAL · zh · 转录 v3 · 纪要 v2             │
│         ├─概览─转录·─纪要─行动项─说话人─导出─任务───────┤
│ 工作    │                                              │
│ 会议    │      （当前标签的主面板）                      │
│ 文档    │                                              │
│ 问答    │                                              │
│ 声纹档案 │                                              │
│         │                                              │
│ 合规    │                                              │
│ 法律保留 │                                              │
│ 删除任务 │                                              │
│ 应急访问 │                                              │
│ 审计     │                                              │
└─────────┴──────────────────────────────────────────────┘
```

侧栏 240px，brand 之下立即放「+ 新建会议」主操作按钮，再之下是「工作」「合规」两段。段标小号 uppercase + 字距 0.04em + `--ink-3`。当前路由的链接用 `--accent-soft` 浅蓝底加 3px 左侧 accent 竖条。

面包屑高 36px，仅在二级以上路由出现，分隔符 `›`，最后一级不可点。

页面头部高度自适应：标题（24px / 600）+ 副标（13px / `--ink-3`）。右侧主操作 ≤ 2 个 + 1 个返回 ghost。

会议内标签栏只在 `/meetings/:id/*` 显示，真实 `<nav><a href>`，当前 tab 2px 下划线 + `--ink-1`，其余 `--ink-3`。

### meeting-web 路由树

```
/login
/meetings                          列表
/meetings/new                      新建
/meetings/:id                      概览 ← 新增 tab
/meetings/:id/audio                上传音频
/meetings/:id/transcript           转录
/meetings/:id/minutes              纪要
/meetings/:id/items                行动项 / 决策 / 风险
/meetings/:id/speakers             说话人确认
/meetings/:id/exports              导出
/meetings/:id/tasks/:taskId        任务进度
/documents                         文档库
/rag                               问答
/speaker-profiles                  声纹档案
/admin/legal-holds                 法律保留
/admin/deletion-jobs               删除任务
/admin/break-glass                 应急访问（路由名保留 break-glass，UI 文案改）
/admin/audit-events                审计事件
```

### ai-worker-web 外壳

桌面 ≥ 1280px：

```
┌──────────────────────────────────────────────────────────┐
│ 运营工作站 · 会议 · 声纹录入                  [zh] [已登录 ▾]│
├──────────────┬───────────────────────────────────────────┤
│ 会议流程     │ mtg_01 季度复盘 · INTERNAL                  │
│              │                                            │
│ 准备         │ 4 · 启动 worker 处理                        │
│  ✓ 1 建会议  │ ────────────────────────────              │
│  ✓ 2 上传录音 │ worker 先跑完声学流水线，纪要和抽取要等你    │
│  ✓ 3a 术语   │ 在第 6 步确认说话人之后才会启动。           │
│  ✓ 3b 文档   │                                            │
│ worker 处理  │ [启动 worker 处理（保持等待）]              │
│  ▶ 4 处理    │                                            │
│    5 认人    │                                            │
│ Java 收尾    │                                            │
│    6a 纪要   │                                            │
│    6c 导出   │                                            │
│              │                                            │
│ 后台         │                                            │
│ worker 运行中 │                                            │
│ Java   等待中 │                                            │
└──────────────┴───────────────────────────────────────────┘
```

顶部 header 48px，左 brand「运营工作站」、中导航「会议 / 声纹录入」、右身份。

wizard rail 200px，8 步分三段对应后端 phase：

- 准备：META / AUDIO / GLOSSARY / DOCUMENTS（后端 phase 进入 WORKER_DAG 之前）
- worker 处理：PROCESS / SPEAKERS（后端 phase 处于 WORKER_DAG_RUNNING ~ WORKER_DAG_DONE）
- Java 收尾：FINALIZE / EXPORT（后端 phase 处于 JAVA_LLM_RUNNING ~ TERMINAL）

每步 `data-state` 表达状态：`completed`（浅青底 + ✓）、`current`（浅蓝底 + 3px 左竖条）、`pending`（默认）、`unreachable`（`--ink-4` 静音）。

rail 底部固定块绑后端 phase：

- `worker · 运行中 / 已完成 / 失败`
- `Java · 等待中 / 运行中 / 已完成 / 失败`

这是 wizard 步骤和后端 phase 的视觉桥，能让运营一眼看出"我现在停在哪、后端在做什么"。

### ai-worker-web 路由

```
/                                   → /meetings
/meetings                           landing（admin BFF 列表 + 两个 action panel）
/meetings/new                       新建会议向导（META 起步）
/meetings/:meetingId                既有会议工作站（AUDIO 起步）
/enrollment                         声纹录入
```

## 后端语义落地

11 条不变量逐条对应 UI 表达。

### 1. Task status × phase

任务进度页顶部两行 metric：

```
状态  ● RUNNING           阶段  ▶ WORKER_DAG_RUNNING
                          ─ JAVA_LLM 等待
                          ─ TERMINAL
```

status 圆点 + 中文：PENDING 灰 / QUEUED 浅蓝 / RUNNING 蓝 / SUCCEEDED 海青 / PARTIAL_SUCCEEDED 琥珀 / FAILED 玫红 / CANCELLED 灰 / ORPHANED 玫红 / CANCEL_PENDING 琥珀。

phase 三段进度条 `.phase-strip`：当前段填 `--accent`，已完成填 `--success`，未到填 `--line-1`。

### 2. Step ownership

step 表新增「来源」列，渲染人话：

```
枚举                   显示
AI_WORKER_CALLBACK    worker 回调
JAVA_TASK_SERVICE     Java 任务服务
```

`AUDIO_UPLOAD` step（Java 在任务创建时即标记 SUCCEEDED）渲染为浅灰行 + 副标「已完成于创建时」。Java 拥有的 step 失败时，retry 按钮 tooltip 写明「Java step 失败只能整体 retry，worker 不参与」。

### 3. STALE 级联

转录被编辑触发后：

- 转录页顶部 `.banner--warn`：「已应用编辑。纪要、行动项、决策、风险、RAG 索引已标记为过期。」+ 「重新生成纪要」「重建 RAG 索引」按钮。
- 纪要 / 行动项 / 导出页若 `stale_status=STALE`，顶部 `.banner--warn`：「转录已被编辑（v3 → 当前 v4），此页基于旧版本」+ 「重新生成」。
- RAG 页 `includeStale` 复选框中文标签「包含已过期片段」，旁边小问号 tooltip 解释，默认 false。

STALE 用琥珀 `--warn`，不是玫红 `--danger`。STALE 不是错误。

### 4. 安全等级阻断

`SECURITY_LEVEL_BLOCKED` 出现在：

- 纪要、行动项、RAG 页：渲染 `SecurityLevelBlockedNotice`（保留组件，重做样式），文案保留现有业务措辞。
- 转录页不受影响。

### 5. AI 建议 vs 业务事实

行动项 / 决策 / 风险卡片按 `acceptanceStatus` 4 色：

- `DRAFT`：浅蓝 3px 左竖条 + 灰色「待确认」pill
- `ACCEPTED`：海青 3px 左竖条 + 「已确认」海青 pill
- `REJECTED`：玫红 3px 左竖条 + 「已驳回」+ 删除线
- `NEEDS_REVIEW`：琥珀 3px 左竖条 + 「待复核（重新生成后字段有变）」+ 字段级 diff（旧值删除线、新值高亮）

会议概览页显眼显示 DRAFT 计数：「12 条建议待确认 →」直跳行动项页。

### 6. RAG coverage 与引用

回答卡顶部三 pill：

- coverage：「仅会议」灰 / 「会议 + 文档」海青
- 引用数：「3 条引用」蓝 / 「无引用 — 仅供参考」琥珀
- 含过期（如果开关开了）：琥珀「含过期片段」

引用块按类型分。会议片段点击跳转 `/meetings/:id/transcript?segmentId=...&startMs=...` 并高亮（reduced motion 下静态填充 1 秒）。

### 7. 连接模式

任务进度页右上角小标签：

- `SSE ●` 绿点（实时）
- `轮询 ●` 琥珀点（降级）
- `已结束 ○` 灰圈（终态后不再连接）

不让 EventSource 字眼出现在 UI。

### 8. Lease 生命周期

`CANCEL_PENDING` 显示「取消中…」琥珀 + spinner，取消按钮置灰。`ORPHANED` 弹 `.banner--danger`「任务因 worker 心跳超时被回收」+ 「重新排队」按钮（调 retry endpoint）。

### 9. 声纹加密透明度

声纹档案详情页底部一行 `--ink-3` 小字：「声纹向量已用 KMS 信封加密存储」。embedding 数组不显示（后端本来也不下发）。enrollment quality score 显示，原始向量不显示。

### 10. 心跳异常

后端 `RUNNING + progress > 0` 心跳不写 callback_events。前端不做特殊处理，progress 涨即视为活着。任务进度页底部 dev-only 小字「最近心跳：3 秒前」辅助排障。

### 11. callback 失败码

`HMAC_INVALID / IDEMPOTENCY_CONFLICT / LEASE_EXPIRED` 等只在任务进度页「事件日志」抽屉显示给运营，默认折叠。普通业务用户不看这层。

## 数据层

### TanStack Query

两个 app 各装 `@tanstack/react-query@^5`、`@tanstack/react-query-devtools@^5`（dev）。`QueryClient` 默认：

```
defaultOptions: {
  queries: { staleTime: 30_000, retry: 2, retryDelay: a => Math.min(200 * 2 ** a, 5_000) },
  mutations: { retry: 0 },
}
```

`QueryClientProvider` 挂在 `main.tsx`。Devtools 仅 dev。

**meeting-web 主要 queries**

```
features/meetings/queries.ts
  useMeetingsQuery({ q, securityLevel })           ['meetings', { q, securityLevel }]
  useMeetingQuery(id)                              ['meeting', id]
  useCreateMeeting()
  useStartTask(meetingId)                          invalidate ['task', '*']

features/tasks/queries.ts
  useTaskQuery(taskId)                             ['task', taskId]
  useRetryTask() / useCancelTask()
  useTaskEventsStream(taskId)                      桥：SSE → setQueryData
  
features/transcript/queries.ts
  useTranscriptQuery(meetingId)                    ['transcript', meetingId]
  useUpdateSegment()                               onError VERSION_CONFLICT → invalidate

features/minutes/queries.ts
  useMinutesQuery(meetingId)
  useRegenerateMinutes()
  
features/items/queries.ts
  useActionItemsQuery / useDecisionsQuery / useRisksQuery / useAcceptItem / useRejectItem

features/rag/queries.ts
  useRagAskMutation()                              不 useQuery，问答是命令式

features/exports/queries.ts
  useExportsQuery / useCreateExport / useExportEventsStream

features/documents/queries.ts
  useDocumentsQuery()

features/speakers/queries.ts
  useSpeakerProfilesQuery / useEnrollMutation / useMeetingSpeakerCandidatesQuery / useConfirmSpeaker

features/admin/queries.ts
  useLegalHoldsQuery / useDeletionJobsQuery / useBreakGlassRequestsQuery / useAuditEventsQuery
```

**ai-worker-web 主要 queries**

```
features/meetings/queries.ts
  useAdminMeetingsQuery()                          ['admin', 'meetings']
  useCreateMeeting() / useAttachDocument() / useUpdateGlossary()
  useMeetingAggregateQuery(meetingId)
  useStartProcessing() / useFinalize()

features/exports/queries.ts
  useCreateExport / useExportPollQuery(meetingId, exportId) refetchInterval=1000 until terminal

features/enrollment/queries.ts
  useSearchPersonsQuery / useEnrollmentSessionMutation / useUploadAudio / usePreview / useCommit
```

SSE 桥 hook：

```ts
function useTaskEventsStream(taskId: string) {
  const client = useQueryClient();
  const [mode, setMode] = useState<'SSE' | 'POLLING'>('SSE');
  useEffect(() => {
    const sub = subscribeTaskEvents(taskId, {
      onEvent: e => client.setQueryData(['task', taskId], cur => sseReducer(cur, e)),
      onFallback: () => setMode('POLLING'),
    });
    return () => sub.close();
  }, [taskId]);
  useQuery({
    queryKey: ['task', taskId],
    queryFn: () => getTask(taskId),
    refetchInterval: mode === 'POLLING' && !isTerminal ? 3000 : false,
  });
  return mode;
}
```

终态自动停连：`isTerminal(status)` 时 `sub.close()` 且 `refetchInterval = false`。

### Zustand

```
shared/stores/auth.ts
  token (内存 only) / user / setToken / clearToken / refresh
  
shared/stores/ui.ts
  sidebarOpen / mobileDrawerOpen / preferences (reducedMotion, density)
  persist 中间件保留 preferences 到 localStorage（不保 token）

features/wizard/store.ts (ai-worker-web)
  meetingId / step / startedProcessing / finalized / exportId / downloadUrl
  迁移自 useWizard 组件本地 state
```

URL 过滤直接用 `useSearchParams`，不进 store。

### 错误处理

每页面渲染自己的错误（行内 banner 或字段 inline），不全局 toast。

共享 `formatApiError(error)` 返回 `{ code, message, retryable, raw }`。沿用 `getUserMessage(code)` 做错误码到中文映射，扩展覆盖所有合规、speaker、export 域错误码。

dev 模式 banner 末尾显示原始 code（小字 `--ink-4`），prod 隐藏。

### 后端改动

只一处：`packages/meeting-contracts/openapi/admin-bff.yaml` 新增 `GET /admin/meetings`。响应结构：

```yaml
items:
  - meetingId / title / securityLevel / createdAt / language / status
  - lastTaskId / lastTaskStatus（可选）
nextCursor: string | null
```

实现：Java `meeting-api-adapter` 加 controller，`app` 层加 query use case，复用既有 `MeetingRepository`。TS / Java codegen 同步。

其他接口契约不动。

## 页面级重构

带 ✱ 是结构重写（拆文件）。

### meeting-web

**鉴权**
- `LoginPage`：单卡居中保留，加 brand 区。username `autocomplete="username"`、password `autocomplete="current-password" type="password"`，禁用 paste 拦截。

**会议域**
- `MeetingListPage`：URL `?q=&securityLevel=` 同步，`Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" })` 模块级实例复用。表格用 `.data-table`，状态 / 安全 pill 上色。空状态加引导：「还没有会议 — 点右上「新建会议」开始」。
- `MeetingCreatePage`：卡片表单，autocomplete `off`，提交期间 button + spinner。
- ✱ `MeetingDetailPage`：拆为 `MeetingOverview` 概览（版本 / 状态 / 安全 / 参与者 / 最近任务 / STALE 统计 / DRAFT 计数）+ `StartTaskPanel` 启动面板（折叠卡片）。
- ✱ `AudioUploadPage`：从 386 行拆为 `AudioUploadIntro` + `AudioPartList` + `AudioUploadSummary` + 现有 `upload-reducer.ts` 不动。进度条用 `.progress`，分片网格用 `.grid` + `.metric` tile，状态 pill 上色，retry 失败分片按钮直观。

**任务**
- `TaskProgressPage`：按§4 重写。phase 三段 strip，step 表 + 来源 / 尝试 / 持续 列，时间 Intl，事件日志抽屉默认折叠。

**内容**
- `TranscriptPage`：状态条改 `.banner--*`，segment row 改 `.segment-row`，时间码 tabular-nums，编辑表单 reason 字段 placeholder `例如：修正错听人名…`，高亮动画 reduced-motion 替代。
- `MinutesPage`：`SafeMarkdown` 渲染，STALE banner + 重新生成按钮。安全阻断时不渲染纪要内容。
- `ItemsPage`：三段（行动项 / 决策 / 风险），每段 `.data-table`，按 `acceptanceStatus` 4 色，NEEDS_REVIEW 显示字段 diff，多选批量接受 / 驳回。
- `ExportsPage`：列表 + 创建表单。SSE 接 `useExportEventsStream`，短链复制按钮带 `aria-live="polite"` 「已复制到剪贴板」反馈。

**说话人**
- `MeetingSpeakerConfirmPage`：候选 person 卡片网格 + 置信度条 + 「认定」按钮。
- ✱ `SpeakerProfilesPage`（603 行重写）：拆为 `SpeakerProfilesPage`（控制器）+ `SpeakerProfileList` + `SpeakerProfileDetail` + `SpeakerEnrollPanel` + `SpeakerSampleUpload` 五个文件。40+ 内联样式全清，统一用类。录音组件改 `.audio-recorder` 类容器。

**文档与 RAG**
- `DocumentsPage`：列表 + 索引状态 pill（ACTIVE / STALE / INDEXING）。
- `RagPage`：拆为 `RagQueryPanel` + `RagAnswerPanel`。textarea 配 `<label htmlFor>`（删 `aria-label`），范围 details 内 fieldset，topN `Intl.NumberFormat`，includeStale 默认 false 中文标签 + tooltip。桌面双列，移动单列堆叠。

**合规（行政表格模式，不用卡片网格）**
- `LegalHoldsPage` / `DeletionJobsPage` / `BreakGlassPage`（UI = 「应急访问」）/ `AuditEventsPage`：`.data-table` 主体 + 顶部过滤 + 创建 / 操作走抽屉。AuditEvents 时间过滤 URL 同步。所有销毁性操作（释放保留 / 撤销应急访问 / 终止删除任务）保留 confirmation modal。

### ai-worker-web

- `MeetingsPage`：用 `useAdminMeetingsQuery` 渲染列表 + 两个 action panel（「新建会议」「声纹录入」）。列表加 「点击进入对应会议工作站」。
- ✱ `MeetingWorkstationPage`（427 行重写）：拆为：
  - `WorkstationShell` 双列容器
  - `WizardRail` 左栏（三段分组 + 步骤状态 + 后端 phase 桥）
  - `StepCanvas` 路由当前步骤
  - `MetaStep / AudioStep / GlossaryStep / DocumentsStep / ProcessStep / SpeakersStep / FinalizeStep / ExportStep`
  - wizard state 进 Zustand store
- `EnrollmentPage`：三段保留，每段 `<h2>` + `name` 属性 + 标签关联。文件 input 包 dropzone 视觉。质量分用环形 svg + 阈值色（< 0.5 琥珀，≥ 0.5 海青）+ 数值 `Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 })`。

## 可访问性与 Web Interface Guidelines

逐条对 Vercel WIG：

- **Skip link**：两个 app shell 第一个元素加 `<a class="skip-link" href="#main-content">跳到主内容</a>`，仅 focus-visible 时显示。
- **焦点环**：全局 `:focus-visible`，所有交互元素生效。禁裸 `outline: none`。
- **表单**：每 input 配 `<label htmlFor>`，不依赖 `aria-label`；`autocomplete` 显式给值；type / inputmode 正确；placeholder 以 `…` 结尾；错误用 `aria-invalid` + `aria-describedby`。
- **动画**：过渡列名禁 `transition: all`；reduced-motion 关闭所有非必要过渡，RAG 高亮降级为 1 秒纯色填充。
- **排版**：tabular-nums 给所有数字列；`text-wrap: balance` 给页面标题；省略号统一 `…`。
- **内容**：长文本 `min-width: 0` + `overflow-wrap` + flex 子项 `min-width: 0`；空状态都给文字 + 引导。
- **列表**：> 50 条用 `content-visibility: auto` 或现有 `VirtualList`；meeting-web 在文档列表、审计事件列表里需要。
- **导航**：URL 反映状态（meeting list 过滤 / RAG scope / audit 时间过滤）；deep link 用 `<a href>`；销毁性操作保留 confirmation modal 或 undo 窗口。
- **触控**：modal `overscroll-behavior: contain`；全局 `body { touch-action: manipulation }`。
- **暗色**：phase 1 不做暗色，`<html>` 给 `color-scheme: light`。
- **i18n**：日期 / 数字 / 货币全走 `Intl.*`；taskId / segmentId / meetingId 加 `translate="no"`。
- **复制**：错误包含下一步动作；按钮文案明确（「确认录入」不是「继续」）；loading 文本 `…` 结尾；不堆叠 emoji 装饰。

## 测试策略

**保留并最终全过的现有测试**

```
meeting-web:
  features/auth/__tests__/LoginPage.test.tsx
  features/meetings/__tests__/MeetingListPage.test.tsx
  features/tasks/__tests__/TaskProgressPage.test.tsx
  features/transcript/__tests__/TranscriptPage.test.tsx
  features/minutes/__tests__/MinutesPage.test.tsx
  features/items/__tests__/ItemsPage.test.tsx
  features/rag/__tests__/RagPage.test.tsx
  features/exports/__tests__/ExportsPage.test.tsx
  features/exports/__tests__/ExportsPage.sse.test.tsx
  features/audio/__tests__/AudioUploadPage.test.tsx
  features/audio/__tests__/upload-reducer.test.ts
  features/speakers/__tests__/SpeakerProfilesPage.test.tsx
  features/speakers/__tests__/MeetingSpeakerConfirmPage.test.tsx
  features/documents/__tests__/DocumentsPage.test.tsx
  features/admin/__tests__/LegalHoldsPage.test.tsx
  features/admin/__tests__/DeletionJobsPage.test.tsx
  features/admin/__tests__/BreakGlassPage.test.tsx
  features/admin/__tests__/AuditEventsPage.test.tsx

ai-worker-web:
  features/wizard/useWizard.test.ts
  shared/auth/store.test.ts
  shared/markdown/SafeMarkdown.test.tsx
  shared/hooks/useDebouncedSearch.test.ts
  shared/list/VirtualList.test.tsx
  shared/api/client.test.ts
```

文案 / class 选取的断言允许更新（「Worker callback」替代「AI_WORKER_CALLBACK」、「应急访问」替代「破玻璃」），业务行为断言必须保留。

**新增测试**

```
meeting-web:
  app/App.test.tsx                 skip link + main landmark + sidebar 段
  features/meetings/MeetingListPage 增加 URL filter 同步测
  features/tasks/TaskProgressPage 增加 phase strip 渲染 + 来源标签人话化
  features/items/ItemsPage 增加 acceptanceStatus 4 色 + NEEDS_REVIEW diff
  features/admin/BreakGlassPage 断言文案为「应急访问」
  features/transcript/TranscriptPage 增加 reduced-motion 高亮替代
  shared/queries/useTaskEventsStream.test.ts  SSE → 轮询切换 setQueryData

ai-worker-web:
  pages/__tests__/MeetingsPage.test.tsx        admin BFF 列表 + action panel
  pages/__tests__/MeetingWorkstationPage.test.tsx  workstation 拆分后冒烟
  pages/__tests__/EnrollmentPage.test.tsx       三段结构 + label htmlFor
  features/wizard/store.test.ts                 zustand store
```

TestRouter 包装加 QueryClientProvider；MSW handlers 保留作为网络 mock。

**契约测试**

`packages/meeting-contracts` 加 `GET /admin/meetings` 的 fixture + Spectral lint pass；`npm run check` 通过。

**最终验证**

```
两个 app: npm test && npm run build
contracts: npm run check && npm run codegen 后 git diff 为空
meeting-api: ./mvnw verify -q（含 ArchUnit + IT + Flyway）
浏览器手测：两 app 桌面 + 移动断点，覆盖 21 个页面
```

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 改动面 ~21 页 + 设计系统 + 数据层迁移 | 实施 plan 分 6 phase 推进；每 phase 独立 PR 可合并 |
| 现有测试按 class 名断言较多 | 允许更新类相关断言；业务断言不动 |
| ai-worker-web admin BFF 新 endpoint | contract codegen + Java verify + adapter ArchUnit 都要过；走完整 CI |
| TanStack Query 引入 SSE 桥 | 终态自动停连 + reduced-motion 配套；增加 `useTaskEventsStream.test.ts` 覆盖切换 |
| 3 个大页面激进重写边缘 case | 重写后跑现有测试 + 手测 + Playwright e2e（仅 meeting-web 已有） |
| Zustand 取代 wizard 本地 state 可能引入跨 meetingId 状态泄漏 | route param 作为 React key 保留（既有做法）；store 在 page unmount 时 reset |

## 不做的事

- 不改业务逻辑、不动数据库 schema、不加新 AI 能力
- 不开新 public API（除 admin BFF 一个 list endpoint）
- 不拆共享 UI 包到 `packages/`
- 不引入 Tailwind / CSS Modules / CSS-in-JS
- 不改 token 存储语义（仍内存 only + cookie 刷新）
- 不动 i18n 框架（zh-CN 直写）
- 不做暗色主题
- 不改 Public DTO / Public API 字段或形状
- 不动 Java / Python 后端业务行为（仅 admin BFF 列表 endpoint）

## 实施分期建议（落到 plan 阶段细化）

供 writing-plans 参考的 phase 切分：

1. **Phase 1**：两 app 设计 token + shell + skip link + 焦点环。最小可见提升，老页面继承新外壳。
2. **Phase 2**：装包（TanStack Query + Zustand）+ QueryClientProvider + auth store 迁移。所有页面切到 useQuery，但 UI 不动。
3. **Phase 3**：meeting-web 关键页（List / Detail 概览 / TaskProgress / Transcript / Rag）按新 token + 后端语义重写。
4. **Phase 4**：合规四页 + 文档 + 纪要 + 行动项 + 导出 + 说话人确认。
5. **Phase 5**：三个大页拆分重写（SpeakerProfilesPage / AudioUploadPage / MeetingWorkstationPage）。
6. **Phase 6**：admin BFF `GET /admin/meetings` 加 endpoint + ai-worker-web landing 接通 + 工作站子组件落位 + 全量回归。

每 phase 是一个独立可合并 PR，CI 全绿。
