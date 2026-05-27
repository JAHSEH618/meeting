# Meeting Frontends Iceberg Refactor — Follow-up Plan

> 跟随 spec `2026-05-27-meeting-frontends-iceberg-refactor-design.md` 与初始 plan `2026-05-27-meeting-frontends-iceberg-refactor.md`。
>
> 主体重构（Phase 1-3 + Phase 4 关键文案 / 行动项 4 色）已于 2026-05-27 合并 master。本文档列出剩余 Phase 4/5/6 任务作为后续 PR 入口。

## 已合并（master）

- Iceberg 设计 token 两 app 落地
- 两 app shell + skip link + 左侧栏 + 焦点环
- TanStack Query + Zustand auth store + SSE 桥
- MeetingListPage URL 同步 + Intl
- MeetingDetailPage 概览 tab + tabbar + 启动面板
- TaskProgressPage phase strip + 来源标签人话化 + 连接模式 pill
- TranscriptPage status banner 系统化
- RagPage 引用块用 Iceberg 类
- BreakGlassPage → 应急访问 文案
- ItemsPage 4 色 acceptance status

## 待合并 Phase 4 余项（中等工作量，每页 ~10-30 行）

每页加一个 `queries.ts`（用 useQuery / useMutation 包装现有 client 调用），把 page 顶层从 useState/useEffect 改为 hooks，错误用 `.banner--danger`，列表用 `.data-table`，状态 pill 用 `.pill--{tone}`。

- `apps/meeting-web/src/features/minutes/MinutesPage.tsx` — STALE banner 用 `.banner--warn`
- `apps/meeting-web/src/features/documents/DocumentsPage.tsx` — 索引状态 pill
- `apps/meeting-web/src/features/exports/ExportsPage.tsx` — useExportEventsStream 桥；短链复制 `aria-live="polite"`
- `apps/meeting-web/src/features/speakers/MeetingSpeakerConfirmPage.tsx` — 候选人 card 网格
- `apps/meeting-web/src/features/admin/LegalHoldsPage.tsx` — `.data-table` 主体 + 创建走 drawer
- `apps/meeting-web/src/features/admin/DeletionJobsPage.tsx` — 同 LegalHolds
- `apps/meeting-web/src/features/admin/AuditEventsPage.tsx` — URL 同步过滤

每页测试已通过现有断言，重写时保持业务文案稳定。

## 待合并 Phase 5：三大页拆分（大工作量）

### SpeakerProfilesPage 603 行 → 5 文件

- `SpeakerProfilesPage.tsx` 控制器（路由 + 顶层状态）
- `SpeakerProfileList.tsx` 列表
- `SpeakerProfileDetail.tsx` 详情面板
- `SpeakerEnrollPanel.tsx` 录入区
- `SpeakerSampleUpload.tsx` 上传 / 录制

40+ 内联样式清掉，新增 `.audio-recorder` / `.upload-dropzone` / `.sample-text-card` 类。

### AudioUploadPage 386 行 → 4 文件

- `AudioUploadPage.tsx` 控制器
- `AudioUploadIntro.tsx` 拖拽区 + 文件信息
- `AudioPartList.tsx` 分片网格
- `AudioUploadSummary.tsx` 进度 / ETA / 字节

`upload-reducer.ts` 保持不变。进度条用 `.progress`，分片网格用 `.grid` + `.metric` tile。

### MeetingWorkstationPage 427 行 → 11 文件 + zustand store

- `apps/ai-worker-web/src/features/wizard/store.ts` 替换 `useWizard.ts`
- `apps/ai-worker-web/src/pages/workstation/WorkstationShell.tsx` 双列
- `apps/ai-worker-web/src/pages/workstation/WizardRail.tsx` 三段分组 + 后端 phase 桥
- `apps/ai-worker-web/src/pages/workstation/StepCanvas.tsx` 路由步骤
- `apps/ai-worker-web/src/pages/workstation/{Meta,Audio,Glossary,Documents,Process,Speakers,Finalize,Export}Step.tsx`

参考 spec §页面级重构 ai-worker-web 部分。

## 待合并 Phase 6：admin BFF + ai-worker-web 收尾（跨 Java/TS）

### contracts 与 Java endpoint

- `packages/meeting-contracts/openapi/admin-bff.yaml` 加 `GET /admin/meetings` 与 `AdminMeetingListResponse` / `AdminMeetingSummary`
- `packages/meeting-contracts/fixtures/admin-bff/list-meetings.json` fixture
- `apps/meeting-api/meeting-api-app/.../app/admin/ListAdminMeetingsUseCase.java`
- `apps/meeting-api/meeting-api-adapter/.../adapter/admin/MeetingAdminController.java` 加 `@GetMapping`
- 单测 + Testcontainers IT

### ai-worker-web 收尾

- `pages/MeetingsPage.tsx` 接 `useAdminMeetingsQuery`，list endpoint 渲染会议
- `pages/EnrollmentPage.tsx` 三段 + `<label htmlFor>` + dropzone + 质量环 svg

## 顺序建议

1. Phase 4 余项（独立小 PR，每页一 commit 或单 PR 全打包）
2. Phase 5.1 SpeakerProfilesPage 拆分（单独 PR）
3. Phase 5.2 AudioUploadPage 拆分（单独 PR）
4. Phase 5.3 MeetingWorkstationPage 拆分（单独 PR，含 wizard store）
5. Phase 6.1 contracts + Java endpoint（单独 PR，需跑 mvn verify）
6. Phase 6.2 ai-worker-web landing 接通 + EnrollmentPage（单独 PR）

每 PR 跑：

```
cd packages/meeting-contracts && npm run check
cd apps/meeting-web && npm test && npm run build
cd apps/ai-worker-web && npm test && npm run build
cd apps/meeting-api && ./mvnw verify -q     # 仅 Phase 6.1 需要
```
