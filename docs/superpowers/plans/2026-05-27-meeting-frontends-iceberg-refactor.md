# Meeting Frontends Iceberg Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `apps/meeting-web` 与 `apps/ai-worker-web` 为冷调瑞典编辑风格 Iceberg 控制台，把后端 task phase/step ownership/STALE/security blocking 等语义显式渲染，并把数据层补齐到 TanStack Query + Zustand。

**Architecture:** 两 app 物理隔离视觉同根。每 app 自有 `:root` token + 语义化 class + 本地 stores/queries 目录。`packages/meeting-contracts` 新增 `GET /admin/meetings` 一个 endpoint，其余后端契约不动。实施分 6 phase，每 phase 可独立合并。

**Tech Stack:** React 18 / React Router 6 / Vite 5 / TypeScript strict / @tanstack/react-query 5 / zustand 4 / Vitest + Testing Library + MSW + Playwright（仅 meeting-web）。

**工作分支：** `frontends-editorial-v2`（已建于 `.worktrees/frontends-editorial-v2`）

**Spec：** `docs/superpowers/specs/2026-05-27-meeting-frontends-iceberg-refactor-design.md`

---

## File Structure

### meeting-web 新建

```
src/shared/
  styles/tokens.css                 Iceberg :root token
  queries/queryClient.ts            QueryClient 工厂 + Provider
  queries/useTaskEventsStream.ts    SSE → setQueryData 桥
  stores/auth.ts                    Zustand auth store
  stores/ui.ts                      Zustand UI store + persist preferences
  utils/formatters.ts               Intl.DateTimeFormat / NumberFormat 实例
  components/Pill.tsx               .pill 通用渲染
  components/Banner.tsx             .banner 通用渲染
  components/PhaseStrip.tsx         任务 3 段 phase 进度
  components/MetricTile.tsx         .metric 渲染
  components/DataTable.tsx          .data-table 包装
  components/Drawer.tsx             右侧抽屉
  components/EmptyState.tsx         空状态
  components/SkipLink.tsx           跳到主内容
  components/SourceLabel.tsx        AI_WORKER_CALLBACK → 「worker 回调」

src/features/<domain>/queries.ts    各 domain 的 useQuery/useMutation
src/features/meetings/MeetingOverviewTab.tsx          新概览 tab
src/features/audio/AudioUploadIntro.tsx               拆分
src/features/audio/AudioPartList.tsx                  拆分
src/features/audio/AudioUploadSummary.tsx             拆分
src/features/speakers/SpeakerProfileList.tsx          拆分
src/features/speakers/SpeakerProfileDetail.tsx        拆分
src/features/speakers/SpeakerEnrollPanel.tsx          拆分
src/features/speakers/SpeakerSampleUpload.tsx         拆分
```

### meeting-web 修改

```
package.json                        新增依赖
src/main.tsx                        QueryClientProvider 包装
src/app/app.css                     完全重写为 Iceberg
src/app/App.tsx                     新 shell（侧栏 + skip link）
src/app/AuthGuard.tsx               从 auth store 读 token
src/services/auth.ts                改为 store dispatcher
src/features/**/*.tsx               按页面清单逐个改造
src/features/**/__tests__/*.tsx     断言适配
```

### ai-worker-web 新建

```
src/shared/styles/tokens.css        Iceberg :root token（同 meeting-web）
src/shared/queries/queryClient.ts
src/shared/queries/useExportPoll.ts 导出轮询 hook
src/shared/stores/auth.ts           （存在则改）
src/shared/stores/ui.ts
src/shared/utils/formatters.ts
src/shared/components/Pill.tsx / Banner.tsx / EmptyState.tsx / SkipLink.tsx
src/features/wizard/store.ts        Zustand wizard store（替换 useWizard 内部 state）
src/features/meetings/queries.ts
src/features/enrollment/queries.ts
src/pages/workstation/WorkstationShell.tsx
src/pages/workstation/WizardRail.tsx
src/pages/workstation/StepCanvas.tsx
src/pages/workstation/MetaStep.tsx
src/pages/workstation/AudioStep.tsx
src/pages/workstation/GlossaryStep.tsx
src/pages/workstation/DocumentsStep.tsx
src/pages/workstation/ProcessStep.tsx
src/pages/workstation/SpeakersStep.tsx
src/pages/workstation/FinalizeStep.tsx
src/pages/workstation/ExportStep.tsx
```

### ai-worker-web 修改

```
package.json
src/main.tsx
src/styles.css                      重写为 Iceberg
src/App.tsx                         新 shell + skip link
src/pages/MeetingsPage.tsx          接入 admin BFF list
src/pages/MeetingWorkstationPage.tsx  收缩为 workstation 路由
src/pages/EnrollmentPage.tsx        三段 + label htmlFor
```

### contracts

```
packages/meeting-contracts/openapi/admin-bff.yaml   GET /admin/meetings 路径
packages/meeting-contracts/fixtures/admin-bff/list-meetings.json   fixture
```

### Java (meeting-api)

```
meeting-api-adapter/.../admin/MeetingAdminController.java    新增 list 方法
meeting-api-app/.../app/admin/ListAdminMeetingsUseCase.java   新增查询用例
meeting-api-adapter/.../admin/__tests__ + meeting-api-app 单测
```

---

## Phase 0：基线验证

### Task 0.1：在 worktree 跑基线测试

**Files:** 无修改，仅验证

- [ ] **Step 1: 切换到 worktree**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/.worktrees/frontends-editorial-v2
git status
git log --oneline -3
```

Expected: branch `frontends-editorial-v2`，HEAD 在 spec commit `6d0c723`

- [ ] **Step 2: 装依赖**

```bash
cd apps/meeting-web && npm install
cd ../ai-worker-web && npm install
cd ../../packages/meeting-contracts && npm install
```

Expected: 所有依赖装好，无错误

- [ ] **Step 3: 跑 meeting-web 基线测试**

```bash
cd apps/meeting-web
npm test 2>&1 | tail -30
```

Expected: 所有现有测试通过，记录文件数和测试数作为基线

- [ ] **Step 4: 跑 ai-worker-web 基线测试**

```bash
cd ../ai-worker-web
npm test 2>&1 | tail -30
```

Expected: 所有现有测试通过

- [ ] **Step 5: 跑 contracts check**

```bash
cd ../../packages/meeting-contracts
npm run check
```

Expected: lint + JSON Schema + enum 一致性 + fixtures 全过

---

## Phase 1：设计 token 与外壳

目标：两 app 安装 Iceberg token、shell（侧栏 / 顶 header）、skip link、焦点环。**老页面继承外壳但内部不动**。

### Task 1.1：meeting-web token 与 utility

**Files:**
- Create: `apps/meeting-web/src/shared/styles/tokens.css`
- Create: `apps/meeting-web/src/shared/utils/formatters.ts`
- Modify: `apps/meeting-web/src/app/app.css`

- [ ] **Step 1: 写 tokens.css**

```css
/* apps/meeting-web/src/shared/styles/tokens.css */
:root {
  /* Surface */
  --surface-base: #f7f9fb;
  --surface-raised: #ffffff;
  --surface-sunken: #eef2f6;

  /* Ink */
  --ink-1: #0f172a;
  --ink-2: #475569;
  --ink-3: #64748b;
  --ink-4: #94a3b8;

  /* Line */
  --line-1: #dde3eb;
  --line-2: #edf1f5;
  --line-3: #f1f5f9;

  /* Accent + status */
  --accent: #1d4ed8;
  --accent-hover: #1e40af;
  --accent-active: #1e3a8a;
  --accent-soft: #dbeafe;
  --accent-ink: #1e3a8a;

  --success: #0e7490;
  --success-soft: #ecfeff;
  --success-ink: #155e75;

  --warn: #b45309;
  --warn-soft: #fef3c7;
  --warn-ink: #92400e;

  --danger: #be123c;
  --danger-soft: #fee2e2;
  --danger-ink: #9f1239;

  --focus: #1d4ed8;

  /* Radius */
  --radius-s: 4px;
  --radius-m: 6px;
  --radius-l: 8px;

  /* Space */
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 20px;
  --space-6: 24px;
  --space-8: 32px;
  --space-10: 40px;

  /* Type */
  --font-sans: Inter, "PingFang SC", "Microsoft YaHei", Arial, sans-serif;

  color-scheme: light;
}

html {
  color-scheme: light;
}

body {
  margin: 0;
  background: var(--surface-base);
  color: var(--ink-1);
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.5;
  -webkit-font-smoothing: antialiased;
  touch-action: manipulation;
}

* { box-sizing: border-box; }

button, input, select, textarea { font: inherit; color: inherit; }
button { cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: 0.6; }
a { color: inherit; }

:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}

.skip-link {
  position: absolute;
  left: -9999px;
  top: 0;
  padding: 8px 12px;
  background: var(--surface-raised);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-s);
  z-index: 100;
}
.skip-link:focus { left: 8px; top: 8px; }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

- [ ] **Step 2: 写 formatters.ts**

```ts
// apps/meeting-web/src/shared/utils/formatters.ts
export const dateFormatter = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "medium",
  timeStyle: "short",
});

export const dateShortFormatter = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "short",
});

export const numberFormatter = new Intl.NumberFormat("zh-CN");

export const percentFormatter = new Intl.NumberFormat("zh-CN", {
  style: "percent",
  maximumFractionDigits: 1,
});

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return dateFormatter.format(date);
}

export function formatMs(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
    : `${m}:${String(s).padStart(2, "0")}`;
}
```

- [ ] **Step 3: 替换 app.css 为 Iceberg 完整版**

参见 `Task 1.2 Step 1` 中的完整 `app.css` 内容。Task 1.1 这一步只是占位，实际写入在 Task 1.2 一起完成。

- [ ] **Step 4: 提交**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/.worktrees/frontends-editorial-v2
git add apps/meeting-web/src/shared/styles/tokens.css apps/meeting-web/src/shared/utils/formatters.ts
git commit -m "feat(meeting-web): add Iceberg design tokens and Intl formatters"
```

### Task 1.2：meeting-web 外壳重写

**Files:**
- Modify: `apps/meeting-web/src/app/app.css`
- Modify: `apps/meeting-web/src/app/App.tsx`
- Create: `apps/meeting-web/src/app/App.test.tsx`
- Create: `apps/meeting-web/src/shared/components/SkipLink.tsx`

- [ ] **Step 1: 写 App.test.tsx 失败测试**

```tsx
// apps/meeting-web/src/app/App.test.tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { App } from "./App";
import { useAuthStore } from "@shared/stores/auth";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

function withProviders(initial: string[]) {
  useAuthStore.setState({ token: "test-token", user: null });
  const client = new QueryClient();
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={initial}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe("App shell", () => {
  it("exposes a skip link to main content", async () => {
    render(withProviders(["/meetings"]));
    const link = await screen.findByRole("link", { name: "跳到主内容" });
    expect(link).toHaveAttribute("href", "#main-content");
  });

  it("renders main landmark with id main-content", async () => {
    render(withProviders(["/meetings"]));
    expect(await screen.findByRole("main")).toHaveAttribute("id", "main-content");
  });

  it("groups sidebar links into 工作 and 合规 sections", async () => {
    render(withProviders(["/meetings"]));
    expect(await screen.findByRole("heading", { name: "工作", level: 3 })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "合规", level: 3 })).toBeInTheDocument();
  });

  it("shows 应急访问 label (not 破玻璃)", async () => {
    render(withProviders(["/meetings"]));
    expect(await screen.findByRole("link", { name: "应急访问" })).toBeInTheDocument();
    expect(screen.queryByText("破玻璃")).not.toBeInTheDocument();
  });

  it("places a 新建会议 primary action at the top of the sidebar", async () => {
    render(withProviders(["/meetings"]));
    const link = await screen.findByRole("link", { name: "新建会议" });
    expect(link).toHaveAttribute("href", "/meetings/new");
  });
});
```

- [ ] **Step 2: 写 SkipLink.tsx**

```tsx
// apps/meeting-web/src/shared/components/SkipLink.tsx
export function SkipLink({ to = "#main-content", children = "跳到主内容" }: { to?: string; children?: React.ReactNode }) {
  return <a className="skip-link" href={to}>{children}</a>;
}
```

- [ ] **Step 3: 重写 app.css 为 Iceberg 完整版**

完整内容（替换现有 304 行）：

```css
/* apps/meeting-web/src/app/app.css */
@import "../shared/styles/tokens.css";

.app-shell {
  display: grid;
  grid-template-columns: 240px 1fr;
  min-height: 100vh;
}

@media (max-width: 1280px) {
  .app-shell { grid-template-columns: 200px 1fr; }
}

@media (max-width: 768px) {
  .app-shell { grid-template-columns: 1fr; }
  .shell__rail { position: fixed; left: -260px; transition: left 200ms ease; z-index: 50; }
  .shell__rail[data-open="true"] { left: 0; }
}

.shell__rail {
  background: var(--surface-raised);
  border-right: 1px solid var(--line-1);
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-height: 100vh;
}

.shell__brand {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--ink-1);
}

.shell__rail-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.shell__rail-section h3 {
  margin: 0 0 var(--space-1);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-3);
}

.shell__rail-link {
  display: block;
  padding: 6px 10px;
  font-size: 13px;
  color: var(--ink-2);
  text-decoration: none;
  border-radius: var(--radius-s);
  border-left: 3px solid transparent;
  margin-left: -3px;
}

.shell__rail-link:hover {
  background: var(--line-3);
  color: var(--ink-1);
}

.shell__rail-link.active {
  background: var(--accent-soft);
  color: var(--accent-ink);
  border-left-color: var(--accent);
  font-weight: 600;
}

.shell__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 36px;
  padding: 0 var(--space-6);
  border-bottom: 1px solid var(--line-2);
  font-size: 12px;
  color: var(--ink-3);
}

.crumbs a { color: var(--ink-3); text-decoration: none; }
.crumbs a:hover { color: var(--ink-1); }
.crumbs__sep { color: var(--ink-4); }
.crumbs__current { color: var(--ink-1); }

.page {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink-1);
  text-wrap: balance;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 13px;
  color: var(--ink-3);
}

.page-actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.tabbar {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--line-1);
  overflow-x: auto;
}

.tabbar a {
  padding: var(--space-3) var(--space-3);
  font-size: 13px;
  color: var(--ink-3);
  text-decoration: none;
  border-bottom: 2px solid transparent;
  white-space: nowrap;
}

.tabbar a:hover { color: var(--ink-1); }
.tabbar a[aria-current="page"] {
  color: var(--ink-1);
  font-weight: 600;
  border-bottom-color: var(--accent);
}

.card {
  background: var(--surface-raised);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-l);
  padding: var(--space-4);
}

.stack { display: flex; flex-direction: column; gap: var(--space-3); min-width: 0; }
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3);
}
.toolbar { display: flex; gap: var(--space-2); flex-wrap: wrap; align-items: center; }

.button {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 36px;
  padding: 0 var(--space-3);
  background: var(--surface-raised);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-m);
  color: var(--ink-1);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  transition: background-color 120ms ease, border-color 120ms ease, color 120ms ease;
}
.button:hover { border-color: var(--ink-4); }
.button--primary { background: var(--accent); color: #fff; border-color: var(--accent); }
.button--primary:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
.button--primary:active { background: var(--accent-active); }
.button--ghost { background: transparent; border-color: transparent; color: var(--ink-2); }
.button--ghost:hover { background: var(--line-3); color: var(--ink-1); border-color: transparent; }
.button--danger { background: var(--danger); color: #fff; border-color: var(--danger); }

.field { display: flex; flex-direction: column; gap: var(--space-1); }
.field__label { font-size: 12px; font-weight: 600; color: var(--ink-2); }
.field__input,
.field input,
.field select,
.field textarea {
  min-height: 36px;
  padding: 6px 10px;
  background: var(--surface-raised);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-m);
  color: var(--ink-1);
  font-size: 13px;
  transition: border-color 120ms ease, box-shadow 120ms ease;
}
.field input:focus-visible,
.field select:focus-visible,
.field textarea:focus-visible {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(29, 78, 216, 0.18);
}
.field__hint { font-size: 11px; color: var(--ink-3); }
.field__error { font-size: 11px; color: var(--danger-ink); }
[aria-invalid="true"] { border-color: var(--danger) !important; }

.pill {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: 22px;
  padding: 0 var(--space-2);
  font-size: 11px;
  font-weight: 500;
  border-radius: 999px;
  background: var(--surface-sunken);
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.pill--info { background: var(--accent-soft); color: var(--accent-ink); }
.pill--success { background: var(--success-soft); color: var(--success-ink); }
.pill--warn { background: var(--warn-soft); color: var(--warn-ink); }
.pill--danger { background: var(--danger-soft); color: var(--danger-ink); }
.pill--neutral { background: var(--surface-sunken); color: var(--ink-2); }

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-4);
  display: inline-block;
}
.dot--info { background: var(--accent); }
.dot--success { background: var(--success); }
.dot--warn { background: var(--warn); }
.dot--danger { background: var(--danger); }

.banner {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--line-1);
  border-left: 3px solid var(--ink-3);
  border-radius: var(--radius-m);
  background: var(--surface-raised);
}
.banner--info { border-left-color: var(--accent); background: var(--accent-soft); }
.banner--success { border-left-color: var(--success); background: var(--success-soft); }
.banner--warn { border-left-color: var(--warn); background: var(--warn-soft); }
.banner--danger { border-left-color: var(--danger); background: var(--danger-soft); }
.banner__title { font-weight: 600; color: var(--ink-1); }
.banner__body { font-size: 13px; color: var(--ink-2); }

.metric {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-3);
  background: var(--surface-raised);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-m);
}
.metric__label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--ink-3); }
.metric__value { font-size: 20px; font-weight: 600; color: var(--ink-1); font-variant-numeric: tabular-nums; }

.data-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--surface-raised);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-l);
  overflow: hidden;
  font-size: 13px;
}
.data-table th,
.data-table td {
  padding: 10px var(--space-3);
  border-bottom: 1px solid var(--line-2);
  text-align: left;
  vertical-align: middle;
}
.data-table th {
  background: var(--surface-sunken);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-3);
}
.data-table td.num,
.data-table th.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.data-table tr:last-child td { border-bottom: 0; }
.data-table tbody tr:hover { background: var(--line-3); }

.progress {
  height: 4px;
  border-radius: 2px;
  background: var(--line-1);
  overflow: hidden;
}
.progress__fill {
  height: 100%;
  background: var(--accent);
  transition: width 200ms ease;
}
.progress--success .progress__fill { background: var(--success); }
.progress--warn .progress__fill { background: var(--warn); }
.progress--danger .progress__fill { background: var(--danger); }

.phase-strip { display: flex; gap: var(--space-1); }
.phase-strip__seg {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  background: var(--line-1);
  position: relative;
  overflow: hidden;
}
.phase-strip__seg[data-state="active"] { background: var(--accent); }
.phase-strip__seg[data-state="done"] { background: var(--success); }
.phase-strip__label {
  margin-top: var(--space-1);
  font-size: 11px;
  color: var(--ink-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.empty-state {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-8);
  text-align: center;
  border: 1px dashed var(--line-1);
  border-radius: var(--radius-l);
  background: var(--surface-raised);
  color: var(--ink-3);
}

.drawer {
  position: fixed;
  top: 0;
  right: 0;
  height: 100vh;
  width: min(420px, 100%);
  background: var(--surface-raised);
  border-left: 1px solid var(--line-1);
  box-shadow: -8px 0 24px rgba(15, 23, 42, 0.08);
  transform: translateX(100%);
  transition: transform 200ms ease;
  z-index: 60;
  overscroll-behavior: contain;
}
.drawer[data-open="true"] { transform: translateX(0); }
```

将整段写入 `apps/meeting-web/src/app/app.css`（完全替换原文件）。

- [ ] **Step 4: 重写 App.tsx 为新 shell**

```tsx
// apps/meeting-web/src/app/App.tsx
import { Suspense, lazy } from "react";
import { Routes, Route, Navigate, NavLink, Outlet, Link } from "react-router-dom";
import { AuthGuard } from "./AuthGuard";
import { SkipLink } from "@shared/components/SkipLink";
import "./app.css";
import { LoginPage } from "@features/auth/LoginPage";
import { MeetingListPage } from "@features/meetings/MeetingListPage";
import { MeetingCreatePage } from "@features/meetings/MeetingCreatePage";
import { MeetingDetailPage } from "@features/meetings/MeetingDetailPage";

const AudioUploadPage = lazy(() => import("@features/audio/AudioUploadPage").then(m => ({ default: m.AudioUploadPage })));
const TranscriptPage = lazy(() => import("@features/transcript/TranscriptPage").then(m => ({ default: m.TranscriptPage })));
const MinutesPage = lazy(() => import("@features/minutes/MinutesPage").then(m => ({ default: m.MinutesPage })));
const ItemsPage = lazy(() => import("@features/items/ItemsPage").then(m => ({ default: m.ItemsPage })));
const RagPage = lazy(() => import("@features/rag/RagPage").then(m => ({ default: m.RagPage })));
const SpeakerProfilesPage = lazy(() => import("@features/speakers/SpeakerProfilesPage").then(m => ({ default: m.SpeakerProfilesPage })));
const MeetingSpeakerConfirmPage = lazy(() => import("@features/speakers/MeetingSpeakerConfirmPage").then(m => ({ default: m.MeetingSpeakerConfirmPage })));
const DocumentsPage = lazy(() => import("@features/documents/DocumentsPage").then(m => ({ default: m.DocumentsPage })));
const ExportsPage = lazy(() => import("@features/exports/ExportsPage").then(m => ({ default: m.ExportsPage })));
const LegalHoldsPage = lazy(() => import("@features/admin/LegalHoldsPage").then(m => ({ default: m.LegalHoldsPage })));
const DeletionJobsPage = lazy(() => import("@features/admin/DeletionJobsPage").then(m => ({ default: m.DeletionJobsPage })));
const BreakGlassPage = lazy(() => import("@features/admin/BreakGlassPage").then(m => ({ default: m.BreakGlassPage })));
const AuditEventsPage = lazy(() => import("@features/admin/AuditEventsPage").then(m => ({ default: m.AuditEventsPage })));
const TaskProgressPage = lazy(() => import("@features/tasks/TaskProgressPage").then(m => ({ default: m.TaskProgressPage })));

const RouteFallback = () => (
  <div className="page" aria-busy="true" role="status" aria-live="polite">加载中…</div>
);

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<AuthGuard />}>
        <Route element={<Shell />}>
          <Route path="/" element={<Navigate to="/meetings" replace />} />
          <Route path="/meetings" element={<MeetingListPage />} />
          <Route path="/meetings/new" element={<MeetingCreatePage />} />
          <Route path="/meetings/:meetingId" element={<MeetingDetailPage />} />
          <Route path="/meetings/:meetingId/audio" element={<Suspense fallback={<RouteFallback />}><AudioUploadPage /></Suspense>} />
          <Route path="/meetings/:meetingId/tasks/:taskId" element={<Suspense fallback={<RouteFallback />}><TaskProgressPage /></Suspense>} />
          <Route path="/meetings/:meetingId/transcript" element={<Suspense fallback={<RouteFallback />}><TranscriptPage /></Suspense>} />
          <Route path="/meetings/:meetingId/minutes" element={<Suspense fallback={<RouteFallback />}><MinutesPage /></Suspense>} />
          <Route path="/meetings/:meetingId/items" element={<Suspense fallback={<RouteFallback />}><ItemsPage /></Suspense>} />
          <Route path="/meetings/:meetingId/speakers" element={<Suspense fallback={<RouteFallback />}><MeetingSpeakerConfirmPage /></Suspense>} />
          <Route path="/meetings/:meetingId/exports" element={<Suspense fallback={<RouteFallback />}><ExportsPage /></Suspense>} />
          <Route path="/rag" element={<Suspense fallback={<RouteFallback />}><RagPage /></Suspense>} />
          <Route path="/speaker-profiles" element={<Suspense fallback={<RouteFallback />}><SpeakerProfilesPage /></Suspense>} />
          <Route path="/documents" element={<Suspense fallback={<RouteFallback />}><DocumentsPage /></Suspense>} />
          <Route path="/admin/legal-holds" element={<Suspense fallback={<RouteFallback />}><LegalHoldsPage /></Suspense>} />
          <Route path="/admin/deletion-jobs" element={<Suspense fallback={<RouteFallback />}><DeletionJobsPage /></Suspense>} />
          <Route path="/admin/break-glass" element={<Suspense fallback={<RouteFallback />}><BreakGlassPage /></Suspense>} />
          <Route path="/admin/audit-events" element={<Suspense fallback={<RouteFallback />}><AuditEventsPage /></Suspense>} />
          <Route path="/speakers" element={<Navigate to="/speaker-profiles" replace />} />
          <Route path="/exports" element={<Navigate to="/meetings" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}

function Shell() {
  return (
    <div className="app-shell">
      <SkipLink />
      <aside className="shell__rail" aria-label="主导航">
        <div className="shell__brand">会议系统</div>
        <Link className="button button--primary" to="/meetings/new">+ 新建会议</Link>

        <nav className="shell__rail-section">
          <h3>工作</h3>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/meetings">会议</NavLink>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/documents">文档</NavLink>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/rag">问答</NavLink>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/speaker-profiles">声纹档案</NavLink>
        </nav>

        <nav className="shell__rail-section">
          <h3>合规</h3>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/admin/legal-holds">法律保留</NavLink>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/admin/deletion-jobs">删除任务</NavLink>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/admin/break-glass">应急访问</NavLink>
          <NavLink className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`} to="/admin/audit-events">审计</NavLink>
        </nav>
      </aside>

      <main id="main-content" className="shell__main">
        <Outlet />
      </main>
    </div>
  );
}
```

- [ ] **Step 5: 跑测试验证 App.test.tsx 全过**

```bash
cd apps/meeting-web
npm test -- src/app/App.test.tsx 2>&1 | tail -20
```

Expected: PASS

注：现有 page 测试可能因为 NavLink 链接文案变化而局部失败（如 RagPage 测试可能查 "RAG" 但现在是 "问答"）。这些在对应页面 Task 修复。

- [ ] **Step 6: 提交**

```bash
git add apps/meeting-web/src/app/ apps/meeting-web/src/shared/components/SkipLink.tsx
git commit -m "feat(meeting-web): rewrite shell with left rail, skip link, focus ring"
```

### Task 1.3：ai-worker-web token 与 shell

**Files:**
- Create: `apps/ai-worker-web/src/shared/styles/tokens.css`
- Create: `apps/ai-worker-web/src/shared/utils/formatters.ts`
- Create: `apps/ai-worker-web/src/shared/components/SkipLink.tsx`
- Modify: `apps/ai-worker-web/src/styles.css`
- Modify: `apps/ai-worker-web/src/App.tsx`

- [ ] **Step 1: 复制 tokens.css**

将 Task 1.1 Step 1 的完整 `tokens.css` 内容写入 `apps/ai-worker-web/src/shared/styles/tokens.css`（内容完全相同）。

- [ ] **Step 2: 复制 formatters.ts**

将 Task 1.1 Step 2 的完整内容写入 `apps/ai-worker-web/src/shared/utils/formatters.ts`。

- [ ] **Step 3: 复制 SkipLink.tsx**

```tsx
// apps/ai-worker-web/src/shared/components/SkipLink.tsx
export function SkipLink({ to = "#main-content", children = "跳到主内容" }: { to?: string; children?: React.ReactNode }) {
  return <a className="skip-link" href={to}>{children}</a>;
}
```

- [ ] **Step 4: 重写 styles.css**

```css
/* apps/ai-worker-web/src/styles.css */
@import "./shared/styles/tokens.css";

.layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.layout__header {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
  background: var(--surface-raised);
  border-bottom: 1px solid var(--line-1);
}

.layout__brand {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--ink-1);
}

.layout__nav {
  display: flex;
  gap: var(--space-3);
}

.layout__nav a {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-3);
  text-decoration: none;
  padding: 4px 8px;
  border-radius: var(--radius-s);
}

.layout__nav a:hover { color: var(--ink-1); background: var(--line-3); }
.layout__nav a.active { color: var(--accent-ink); background: var(--accent-soft); font-weight: 600; }

.layout__main {
  flex: 1;
  padding: var(--space-6);
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
  min-width: 0;
}

/* Workstation 双列 */
.workstation {
  display: grid;
  grid-template-columns: 200px 1fr;
  gap: var(--space-5);
  min-height: 70vh;
}

@media (max-width: 960px) {
  .workstation { grid-template-columns: 1fr; }
}

.workstation__rail {
  background: var(--surface-raised);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-l);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  font-size: 12px;
}

.workstation__canvas {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.wizard__group {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.wizard__group h4 {
  margin: 0 0 var(--space-1);
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-3);
}

.wizard__step {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 8px;
  border-radius: var(--radius-s);
  border-left: 3px solid transparent;
  margin-left: -3px;
  color: var(--ink-2);
}

.wizard__step[data-state="completed"] { background: var(--success-soft); color: var(--success-ink); }
.wizard__step[data-state="current"] { background: var(--accent-soft); color: var(--accent-ink); border-left-color: var(--accent); font-weight: 600; }
.wizard__step[data-state="unreachable"] { color: var(--ink-4); }

.wizard__backend-summary {
  border-top: 1px solid var(--line-1);
  padding-top: var(--space-2);
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: var(--ink-3);
}

/* 通用 class（复制 meeting-web 同名） */
.card { background: var(--surface-raised); border: 1px solid var(--line-1); border-radius: var(--radius-l); padding: var(--space-4); }
.stack { display: flex; flex-direction: column; gap: var(--space-3); min-width: 0; }
.toolbar { display: flex; gap: var(--space-2); flex-wrap: wrap; align-items: center; }

.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-4); }
.page-title { margin: 0; font-size: 22px; font-weight: 600; color: var(--ink-1); text-wrap: balance; }
.page-subtitle { margin: var(--space-1) 0 0; font-size: 13px; color: var(--ink-3); }

.button { display: inline-flex; align-items: center; gap: var(--space-2); min-height: 36px; padding: 0 var(--space-3); background: var(--surface-raised); border: 1px solid var(--line-1); border-radius: var(--radius-m); color: var(--ink-1); font-size: 13px; font-weight: 500; text-decoration: none; transition: background-color 120ms ease, border-color 120ms ease, color 120ms ease; }
.button:hover:not(:disabled) { border-color: var(--ink-4); }
.button:disabled { opacity: 0.55; cursor: not-allowed; }
.button--primary { background: var(--accent); color: #fff; border-color: var(--accent); }
.button--primary:hover:not(:disabled) { background: var(--accent-hover); border-color: var(--accent-hover); }
.button--primary:active:not(:disabled) { background: var(--accent-active); }
.button--secondary { background: var(--surface-raised); color: var(--accent-ink); border-color: var(--accent); }
.button--secondary:hover:not(:disabled) { background: var(--accent-soft); }
.button--ghost { background: transparent; border-color: transparent; color: var(--ink-2); }

.field { display: flex; flex-direction: column; gap: var(--space-1); }
.field__label { font-size: 12px; font-weight: 600; color: var(--ink-2); }
.input, .textarea, .select {
  width: 100%; padding: 6px 10px; min-height: 36px;
  background: var(--surface-raised); border: 1px solid var(--line-1); border-radius: var(--radius-m);
  color: var(--ink-1); font-size: 13px;
}
.input:focus-visible, .textarea:focus-visible, .select:focus-visible {
  outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px rgba(29, 78, 216, 0.18);
}
.input[aria-invalid="true"] { border-color: var(--danger); }

.row { display: flex; gap: var(--space-2); align-items: center; flex-wrap: wrap; }

.error { color: var(--danger-ink); font-size: 13px; }

.pill { display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 8px; font-size: 11px; font-weight: 500; border-radius: 999px; background: var(--surface-sunken); color: var(--ink-2); font-variant-numeric: tabular-nums; }
.pill--info { background: var(--accent-soft); color: var(--accent-ink); }
.pill--success { background: var(--success-soft); color: var(--success-ink); }
.pill--warn { background: var(--warn-soft); color: var(--warn-ink); }
.pill--danger { background: var(--danger-soft); color: var(--danger-ink); }

.banner { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-3); border: 1px solid var(--line-1); border-left: 3px solid var(--ink-3); border-radius: var(--radius-m); background: var(--surface-raised); }
.banner--info { border-left-color: var(--accent); background: var(--accent-soft); }
.banner--success { border-left-color: var(--success); background: var(--success-soft); }
.banner--warn { border-left-color: var(--warn); background: var(--warn-soft); }
.banner--danger { border-left-color: var(--danger); background: var(--danger-soft); }

.empty-state { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-8); text-align: center; border: 1px dashed var(--line-1); border-radius: var(--radius-l); background: var(--surface-raised); color: var(--ink-3); }

.chip { display: inline-flex; align-items: center; padding: 4px 8px; margin: 2px; border-radius: 12px; background: var(--accent-soft); color: var(--accent-ink); font-size: 12px; }
.chip__remove { background: transparent; border: 0; color: inherit; margin-left: 4px; padding: 0 4px; cursor: pointer; border-radius: var(--radius-s); font-size: 14px; line-height: 1; }
.chip__remove:hover { background: rgba(29, 78, 216, 0.18); }

.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
```

- [ ] **Step 5: 重写 App.tsx**

```tsx
// apps/ai-worker-web/src/App.tsx
import { Suspense, lazy } from "react";
import { NavLink, Route, Routes, useParams } from "react-router-dom";
import { useAuth } from "@/shared/auth/useAuth";
import { SkipLink } from "@/shared/components/SkipLink";
import { EnrollmentPage } from "@/pages/EnrollmentPage";
import { MeetingsPage } from "@/pages/MeetingsPage";

const MeetingWorkstationPage = lazy(() =>
  import("@/pages/MeetingWorkstationPage").then((m) => ({ default: m.MeetingWorkstationPage })),
);

function MeetingWorkstationRoute() {
  const { meetingId } = useParams<{ meetingId?: string }>();
  return <MeetingWorkstationPage key={meetingId ?? "new"} />;
}

export default function App() {
  const { ready, token } = useAuth();
  if (!ready) {
    return (
      <div className="layout">
        <main className="layout__main" aria-busy="true" role="status" aria-live="polite">加载中…</main>
      </div>
    );
  }
  return (
    <div className="layout">
      <SkipLink />
      <header className="layout__header">
        <strong className="layout__brand">运营工作站</strong>
        <nav className="layout__nav" aria-label="主导航">
          <NavLink to="/meetings" className={({ isActive }) => (isActive ? "active" : "")}>会议</NavLink>
          <NavLink to="/enrollment" className={({ isActive }) => (isActive ? "active" : "")}>声纹录入</NavLink>
        </nav>
        <span style={{ fontSize: 12, color: "var(--ink-3)" }}>{token ? "已登录" : "未登录"}</span>
      </header>
      <main id="main-content" className="layout__main">
        <Suspense fallback={<div aria-busy="true" role="status">加载中…</div>}>
          <Routes>
            <Route path="/" element={<MeetingsPage />} />
            <Route path="/meetings" element={<MeetingsPage />} />
            <Route path="/meetings/new" element={<MeetingWorkstationRoute />} />
            <Route path="/meetings/:meetingId" element={<MeetingWorkstationRoute />} />
            <Route path="/enrollment" element={<EnrollmentPage />} />
          </Routes>
        </Suspense>
      </main>
    </div>
  );
}
```

- [ ] **Step 6: 跑 ai-worker-web 测试确认无回归**

```bash
cd apps/ai-worker-web
npm test 2>&1 | tail -20
```

Expected: 现有测试仍通过（useWizard、SafeMarkdown、auth store、debouncedSearch、VirtualList、client 这些与 shell 无关）。

- [ ] **Step 7: 提交**

```bash
git add apps/ai-worker-web/src/styles.css apps/ai-worker-web/src/App.tsx apps/ai-worker-web/src/shared/
git commit -m "feat(ai-worker-web): rewrite shell with Iceberg tokens and skip link"
```

### Task 1.4：Phase 1 闸门

- [ ] **Step 1: 双 app build + test 跑齐**

```bash
cd apps/meeting-web && npm test && npm run build 2>&1 | tail
cd ../ai-worker-web && npm test && npm run build 2>&1 | tail
```

Expected: 两 app 全绿。若 meeting-web 出现 NavLink 文案断言失败（如 RagPage.test 中 "RAG" → "问答"），允许更新断言。

- [ ] **Step 2: 浏览器手测**

```bash
cd apps/meeting-web && npm run dev &
cd ../ai-worker-web && npm run dev &
```

打开 `http://localhost:5173/meetings` 和 `http://localhost:5174/workstation/meetings`（或实际端口），按 Tab 验证 skip link 可见、焦点环可见、左侧栏分组正确。

---

## Phase 2：TanStack Query + Zustand 数据层

目标：装包、配置 client、auth 迁到 Zustand、关键页迁到 useQuery、SSE 桥就位。**UI 不动**。

### Task 2.1：装包与基础设施

**Files:**
- Modify: `apps/meeting-web/package.json`
- Modify: `apps/ai-worker-web/package.json`
- Create: `apps/meeting-web/src/shared/queries/queryClient.ts`
- Create: `apps/ai-worker-web/src/shared/queries/queryClient.ts`
- Modify: `apps/meeting-web/src/main.tsx`
- Modify: `apps/ai-worker-web/src/main.tsx`

- [ ] **Step 1: 加依赖到 meeting-web**

```bash
cd apps/meeting-web
npm install @tanstack/react-query@^5 zustand@^4
npm install --save-dev @tanstack/react-query-devtools@^5
```

- [ ] **Step 2: 加依赖到 ai-worker-web**

```bash
cd ../ai-worker-web
npm install @tanstack/react-query@^5 zustand@^4
npm install --save-dev @tanstack/react-query-devtools@^5
```

- [ ] **Step 3: 写 queryClient 工厂（两 app 同样）**

```ts
// apps/meeting-web/src/shared/queries/queryClient.ts
import { QueryClient } from "@tanstack/react-query";

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: 2,
        retryDelay: (a) => Math.min(200 * 2 ** a, 5_000),
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: 0,
      },
    },
  });
}
```

同样的内容写入 `apps/ai-worker-web/src/shared/queries/queryClient.ts`。

- [ ] **Step 4: 包装 main.tsx（meeting-web）**

```tsx
// apps/meeting-web/src/main.tsx
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import { App } from "@app/App";
import { createQueryClient } from "@shared/queries/queryClient";
import { initAuthFromStorage } from "@services/auth";
import "./index.css";

const client = createQueryClient();

initAuthFromStorage();

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
      {import.meta.env.DEV ? <ReactQueryDevtools position="bottom-right" /> : null}
    </QueryClientProvider>
  </React.StrictMode>,
);
```

如果 `main.tsx` 现有结构略有不同，保留原有 import，只增加 `QueryClientProvider` 包裹和 `ReactQueryDevtools` 条件渲染。

- [ ] **Step 5: 包装 main.tsx（ai-worker-web）**

```tsx
// apps/ai-worker-web/src/main.tsx
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import App from "./App";
import { createQueryClient } from "@/shared/queries/queryClient";
import "./styles.css";

const client = createQueryClient();

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <BrowserRouter basename="/workstation">
        <App />
      </BrowserRouter>
      {import.meta.env.DEV ? <ReactQueryDevtools position="bottom-right" /> : null}
    </QueryClientProvider>
  </React.StrictMode>,
);
```

`basename="/workstation"` 视项目实际配置保留或删除，参考现有 main.tsx。

- [ ] **Step 6: 跑两 app 测试**

```bash
cd ../meeting-web && npm test 2>&1 | tail
cd ../ai-worker-web && npm test 2>&1 | tail
```

Expected: 现有测试通过。测试中用 TestRouter 包装的，可能需要补 QueryClientProvider — 在 Task 2.4 集中修。

- [ ] **Step 7: 提交**

```bash
git add apps/meeting-web/package.json apps/meeting-web/package-lock.json apps/meeting-web/src/main.tsx apps/meeting-web/src/shared/queries/
git add apps/ai-worker-web/package.json apps/ai-worker-web/package-lock.json apps/ai-worker-web/src/main.tsx apps/ai-worker-web/src/shared/queries/
git commit -m "feat(both): add TanStack Query and zustand, wrap apps with QueryClientProvider"
```

### Task 2.2：auth store 迁移（meeting-web）

**Files:**
- Create: `apps/meeting-web/src/shared/stores/auth.ts`
- Modify: `apps/meeting-web/src/services/auth.ts`
- Modify: `apps/meeting-web/src/app/AuthGuard.tsx`

- [ ] **Step 1: 读现有 auth.ts 结构**

```bash
cat apps/meeting-web/src/services/auth.ts
cat apps/meeting-web/src/app/AuthGuard.tsx
```

了解 token / user shape。

- [ ] **Step 2: 写 auth store**

```ts
// apps/meeting-web/src/shared/stores/auth.ts
import { create } from "zustand";

export interface AuthUser {
  userId: string;
  displayName: string;
  tenantId: string;
}

interface AuthState {
  token: string | null;
  user: AuthUser | null;
  ready: boolean;
  setSession: (token: string, user: AuthUser) => void;
  clear: () => void;
  markReady: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  token: null,
  user: null,
  ready: false,
  setSession: (token, user) => set({ token, user }),
  clear: () => set({ token: null, user: null }),
  markReady: () => set({ ready: true }),
}));

export function selectToken(s: AuthState) { return s.token; }
export function selectUser(s: AuthState) { return s.user; }
```

- [ ] **Step 3: 改 services/auth.ts 为 store dispatcher**

具体改动取决于原文件结构。常见做法：把 `let token: string | null = null` 这类模块级变量替换为 `useAuthStore.getState()` / `useAuthStore.setState()` 调用，导出的 `getToken / setToken / login / logout` 函数都委托给 store。

```ts
// apps/meeting-web/src/services/auth.ts （示意，按现有逻辑改写）
import { useAuthStore } from "@shared/stores/auth";
// import 现有 api 调用

export function getToken(): string | null {
  return useAuthStore.getState().token;
}

export async function login(username: string, password: string) {
  // 现有 fetch /api/auth/login
  const { token, user } = await callLogin(username, password);
  useAuthStore.getState().setSession(token, user);
}

export async function logout() {
  // 现有 fetch /api/auth/logout
  await callLogout();
  useAuthStore.getState().clear();
}

export async function initAuthFromStorage() {
  // 现有 /me 拉用户
  try {
    const me = await fetchMe();
    if (me) useAuthStore.getState().setSession(me.token ?? "", me.user);
  } catch { /* 401 ignored */ }
  useAuthStore.getState().markReady();
}
```

保留现有公开签名以减小调用方修改面。

- [ ] **Step 4: 改 AuthGuard.tsx 读 store**

```tsx
// apps/meeting-web/src/app/AuthGuard.tsx
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuthStore } from "@shared/stores/auth";

export function AuthGuard() {
  const token = useAuthStore((s) => s.token);
  const ready = useAuthStore((s) => s.ready);
  const location = useLocation();
  if (!ready) {
    return <div className="page" aria-busy="true" role="status" aria-live="polite">加载中…</div>;
  }
  if (!token) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}
```

- [ ] **Step 5: 跑测试**

```bash
cd apps/meeting-web && npm test 2>&1 | tail -30
```

Expected: 现有 LoginPage 测试与 AuthGuard 行为测试通过。如果有用 useState 直接读 auth 的测试，更新为 setState store。

- [ ] **Step 6: 提交**

```bash
git add apps/meeting-web/src/shared/stores/auth.ts apps/meeting-web/src/services/auth.ts apps/meeting-web/src/app/AuthGuard.tsx
git commit -m "refactor(meeting-web): migrate auth to zustand store"
```

### Task 2.3：SSE 桥 hook（meeting-web）

**Files:**
- Create: `apps/meeting-web/src/shared/queries/useTaskEventsStream.ts`
- Create: `apps/meeting-web/src/shared/queries/__tests__/useTaskEventsStream.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
// apps/meeting-web/src/shared/queries/__tests__/useTaskEventsStream.test.ts
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { useTaskEventsStream } from "../useTaskEventsStream";
import * as client from "@shared/api/client";
import type { ProcessingTask } from "@shared/api/types";

function makeWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // eslint-disable-next-line react/display-name
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe("useTaskEventsStream", () => {
  it("starts in SSE mode and switches to POLLING on fallback", async () => {
    const fakeTask: ProcessingTask = {
      taskId: "t1", meetingId: "m1", status: "RUNNING", phase: "WORKER_DAG_RUNNING",
      attemptNo: 1, steps: [], retryable: false,
    } as ProcessingTask;
    vi.spyOn(client, "getTask").mockResolvedValue(fakeTask);
    let triggerFallback: () => void = () => {};
    vi.spyOn(client, "subscribeTaskEvents").mockImplementation((_, { onFallback }) => {
      triggerFallback = onFallback ?? (() => {});
      return { close: () => {} };
    });

    const { result } = renderHook(() => useTaskEventsStream("t1"), { wrapper: makeWrapper() });
    expect(result.current.connectionMode).toBe("SSE");

    act(() => triggerFallback());

    expect(result.current.connectionMode).toBe("POLLING");
  });

  it("disconnects when task reaches terminal status", async () => {
    const fakeTask: ProcessingTask = {
      taskId: "t1", meetingId: "m1", status: "SUCCEEDED", phase: "TERMINAL",
      attemptNo: 1, steps: [], retryable: false,
    } as ProcessingTask;
    vi.spyOn(client, "getTask").mockResolvedValue(fakeTask);
    const closeSpy = vi.fn();
    vi.spyOn(client, "subscribeTaskEvents").mockImplementation(() => ({ close: closeSpy }));

    const { result, unmount } = renderHook(() => useTaskEventsStream("t1"), { wrapper: makeWrapper() });
    unmount();
    expect(closeSpy).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 跑测试验证失败**

```bash
cd apps/meeting-web
npm test -- src/shared/queries/__tests__/useTaskEventsStream.test.ts 2>&1 | tail
```

Expected: FAIL（hook 未实现）

- [ ] **Step 3: 写 hook**

```ts
// apps/meeting-web/src/shared/queries/useTaskEventsStream.ts
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getTask, subscribeTaskEvents, type TaskEventSubscription } from "@shared/api/client";
import { sseReducer, createInitialSnapshot, type TaskSnapshot } from "@shared/utils/sse-reducer";
import type { ProcessingTask, ProcessingTaskStatus } from "@shared/api/types";

const TERMINAL: ProcessingTaskStatus[] = ["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"];
const POLL_INTERVAL = 3000;

function isTerminal(s: ProcessingTaskStatus | null | undefined) {
  return s ? TERMINAL.includes(s) : false;
}

function snapshotFromTask(task: ProcessingTask): TaskSnapshot {
  return {
    ...createInitialSnapshot(),
    taskId: task.taskId,
    meetingId: task.meetingId ?? "",
    status: task.status,
    phase: task.phase,
    attemptNo: task.attemptNo,
    currentStep: task.currentStep ?? null,
    lastErrorCode: task.lastErrorCode ?? null,
    retryable: task.retryable ?? false,
    steps: task.steps,
    completedSteps: task.steps.filter((s) => s.status === "SUCCEEDED").map((s) => s.stepName),
  };
}

export function useTaskEventsStream(taskId: string) {
  const queryClient = useQueryClient();
  const [connectionMode, setConnectionMode] = useState<"SSE" | "POLLING">("SSE");
  const subRef = useRef<TaskEventSubscription | null>(null);
  const lastEventId = useRef<string | null>(null);

  const { data, isPending, error } = useQuery<TaskSnapshot>({
    queryKey: ["task", taskId],
    queryFn: async () => snapshotFromTask(await getTask(taskId)),
    enabled: !!taskId,
    refetchInterval: (q) => {
      const s = q.state.data?.status;
      return connectionMode === "POLLING" && !isTerminal(s) ? POLL_INTERVAL : false;
    },
  });

  useEffect(() => {
    if (!taskId) return;
    if (isTerminal(data?.status)) {
      subRef.current?.close();
      subRef.current = null;
      return;
    }
    subRef.current = subscribeTaskEvents(taskId, {
      lastEventId: lastEventId.current,
      onEvent: (event) => {
        lastEventId.current = event.eventId;
        queryClient.setQueryData<TaskSnapshot>(["task", taskId], (cur) =>
          sseReducer(cur && cur.taskId ? cur : createInitialSnapshot(), event),
        );
      },
      onFallback: () => setConnectionMode("POLLING"),
    });
    return () => {
      subRef.current?.close();
      subRef.current = null;
    };
  }, [taskId, data?.status, queryClient]);

  return {
    snapshot: data ?? createInitialSnapshot(),
    connectionMode: isTerminal(data?.status) ? "TERMINATED" as const : connectionMode,
    isPending,
    error,
  };
}
```

- [ ] **Step 4: 跑测试通过**

```bash
npm test -- src/shared/queries/__tests__/useTaskEventsStream.test.ts 2>&1 | tail
```

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add apps/meeting-web/src/shared/queries/useTaskEventsStream.ts apps/meeting-web/src/shared/queries/__tests__/
git commit -m "feat(meeting-web): add useTaskEventsStream hook bridging SSE to react-query"
```

### Task 2.4：测试 wrapper 适配 QueryClientProvider

**Files:**
- Look for: `apps/meeting-web/src/__tests__/utils/TestRouter.tsx` 或 `test-setup.ts`

- [ ] **Step 1: 找现有 TestRouter**

```bash
cd apps/meeting-web
grep -rn "TestRouter" src/ | head
```

- [ ] **Step 2: 包装 QueryClientProvider**

如果 TestRouter 存在，改造为：

```tsx
import { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

export function TestRouter({ children, initialEntries = ["/"] }: { children: ReactNode; initialEntries?: string[] }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}
```

如果不存在，在 `src/__tests__/utils/TestRouter.tsx` 新建。

- [ ] **Step 3: 跑全量测试**

```bash
npm test 2>&1 | tail -50
```

Expected: 大部分通过。任何因 useQuery 未包装报错的测试在对应 page Task 中修。

- [ ] **Step 4: 提交**

```bash
git add apps/meeting-web/src/__tests__/utils/TestRouter.tsx
git commit -m "test(meeting-web): wrap TestRouter with QueryClientProvider"
```

### Task 2.5：feature queries 文件骨架

**Files:** 仅创建文件占位，具体 hook 在对应 page Task 时补全。

- [ ] **Step 1: 批量创建 queries 文件**

为以下 feature 各创建 `queries.ts`，每个文件先写空导出：

```
apps/meeting-web/src/features/meetings/queries.ts
apps/meeting-web/src/features/tasks/queries.ts
apps/meeting-web/src/features/transcript/queries.ts
apps/meeting-web/src/features/minutes/queries.ts
apps/meeting-web/src/features/items/queries.ts
apps/meeting-web/src/features/rag/queries.ts
apps/meeting-web/src/features/exports/queries.ts
apps/meeting-web/src/features/documents/queries.ts
apps/meeting-web/src/features/speakers/queries.ts
apps/meeting-web/src/features/admin/queries.ts
apps/ai-worker-web/src/features/meetings/queries.ts
apps/ai-worker-web/src/features/enrollment/queries.ts
apps/ai-worker-web/src/features/exports/queries.ts
```

每文件初始内容：

```ts
// 由 Phase 3-6 各 page Task 补全 hook
export {};
```

- [ ] **Step 2: 提交**

```bash
git add apps/meeting-web/src/features/*/queries.ts apps/ai-worker-web/src/features/*/queries.ts
git commit -m "chore: scaffold per-feature queries.ts"
```

### Task 2.6：Phase 2 闸门

- [ ] **Step 1: 全量测试 + 构建**

```bash
cd apps/meeting-web && npm test && npm run build
cd ../ai-worker-web && npm test && npm run build
```

Expected: 双 app 全绿。

---

## Phase 3：meeting-web 关键页

目标：MeetingList、MeetingDetail（含新概览 tab）、TaskProgress、Transcript、RAG 用新 token + 后端语义重写。

### Task 3.1：MeetingListPage URL 同步 + Intl 格式化

**Files:**
- Modify: `apps/meeting-web/src/features/meetings/queries.ts`
- Modify: `apps/meeting-web/src/features/meetings/MeetingListPage.tsx`
- Modify: `apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx`

- [ ] **Step 1: 写 useMeetingsQuery**

```ts
// apps/meeting-web/src/features/meetings/queries.ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createMeeting, createProcessingTask, getMeeting, listMeetings } from "@shared/api/client";
import type { Meeting } from "@shared/api/types";

export function useMeetingsQuery() {
  return useQuery<{ items: Meeting[] }>({
    queryKey: ["meetings"],
    queryFn: () => listMeetings(),
  });
}

export function useMeetingQuery(meetingId: string | undefined) {
  return useQuery<Meeting>({
    queryKey: ["meeting", meetingId],
    queryFn: () => getMeeting(meetingId!),
    enabled: !!meetingId,
  });
}

export function useCreateMeeting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: createMeeting,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["meetings"] }),
  });
}

export function useStartTask(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (audioFileId: string) => createProcessingTask(meetingId, audioFileId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["meeting", meetingId] });
      qc.invalidateQueries({ queryKey: ["meeting-task", meetingId] });
    },
  });
}
```

- [ ] **Step 2: 写失败测试 — URL 同步**

```tsx
// 修改 apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx
// 在文件末尾新增：

import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";

function LocationProbe() {
  const loc = useLocation();
  return <output data-testid="location-search">{loc.search}</output>;
}

it("hydrates filters from URL and writes back to query string", async () => {
  const user = userEvent.setup();
  render(
    <TestRouter initialEntries={["/meetings?q=roadmap&securityLevel=SECRET"]}>
      <MeetingListPage />
      <LocationProbe />
    </TestRouter>,
  );

  expect(await screen.findByLabelText("搜索会议")).toHaveValue("roadmap");
  expect(screen.getByLabelText("安全等级")).toHaveValue("SECRET");

  await user.clear(screen.getByLabelText("搜索会议"));
  await user.type(screen.getByLabelText("搜索会议"), "新版");
  await user.selectOptions(screen.getByLabelText("安全等级"), "INTERNAL");

  await waitFor(() => {
    expect(screen.getByTestId("location-search").textContent ?? "").toContain("q=");
    expect(screen.getByTestId("location-search").textContent ?? "").toContain("securityLevel=INTERNAL");
  });
});

it("renders dates with Intl.DateTimeFormat zh-CN", async () => {
  render(<TestRouter><MeetingListPage /></TestRouter>);
  await screen.findByText(/季度复盘|测试会议/);
  // 任一行的时间格式应该是 zh-CN 中等格式（包含 "年"）
  expect(screen.getByText(/\d{4}\/\d{2}\/\d{2}|\d+年\d+月/)).toBeInTheDocument();
});
```

- [ ] **Step 3: 跑测试验证 FAIL**

```bash
npm test -- src/features/meetings/__tests__/MeetingListPage.test.tsx 2>&1 | tail
```

Expected: 新增的两个 case FAIL。

- [ ] **Step 4: 重写 MeetingListPage**

```tsx
// apps/meeting-web/src/features/meetings/MeetingListPage.tsx
import { useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useMeetingsQuery } from "./queries";
import { formatDate } from "@shared/utils/formatters";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";
import type { Meeting } from "@shared/api/types";

const SECURITY_LEVELS = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"] as const;

const STATUS_LABEL: Record<string, string> = {
  CREATED: "新建",
  PROCESSING: "处理中",
  READY: "可用",
  ARCHIVED: "已归档",
  DELETED: "已删除",
};

const SECURITY_TONE: Record<string, string> = {
  PUBLIC: "pill--neutral",
  INTERNAL: "pill--info",
  CONFIDENTIAL: "pill--warn",
  SECRET: "pill--danger",
};

export function MeetingListPage() {
  const [params, setParams] = useSearchParams();
  const keyword = params.get("q") ?? "";
  const security = params.get("securityLevel") ?? "ALL";

  const { data, isPending, error } = useMeetingsQuery();
  const meetings = data?.items ?? [];

  const filtered = useMemo(() => {
    return meetings.filter((m) => {
      const titleMatch = m.title.toLowerCase().includes(keyword.trim().toLowerCase());
      const securityMatch = security === "ALL" || m.securityLevel === security;
      return titleMatch && securityMatch;
    });
  }, [meetings, keyword, security]);

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (!v || v === "ALL") merged.delete(k);
      else merged.set(k, v);
    }
    setParams(merged, { replace: true });
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">会议</h1>
          <p className="page-subtitle">查看会议处理状态并进入详情。</p>
        </div>
        <div className="page-actions">
          <Link className="button button--primary" to="/meetings/new">+ 新建会议</Link>
        </div>
      </header>

      <section className="card stack">
        <div className="toolbar">
          <div className="field" style={{ flex: 1, minWidth: 220 }}>
            <label className="field__label" htmlFor="meeting-search">搜索会议</label>
            <input
              id="meeting-search"
              type="search"
              name="q"
              autoComplete="off"
              placeholder="按标题搜索…"
              value={keyword}
              onChange={(e) => update({ q: e.target.value })}
            />
          </div>
          <div className="field" style={{ minWidth: 180 }}>
            <label className="field__label" htmlFor="meeting-security">安全等级</label>
            <select
              id="meeting-security"
              name="securityLevel"
              value={security}
              onChange={(e) => update({ securityLevel: e.target.value })}
            >
              <option value="ALL">全部安全等级</option>
              {SECURITY_LEVELS.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
        </div>

        {isPending ? <p className="page-subtitle" aria-live="polite">加载中…</p> : null}
        {error ? (
          <div className="banner banner--danger" role="alert">
            <strong className="banner__title">列表加载失败</strong>
            <span className="banner__body">{(error as ApiClientError).code ? getUserMessage((error as ApiClientError).code!) : "请稍后重试"}</span>
          </div>
        ) : null}
        {!isPending && !error && filtered.length === 0 ? (
          <div className="empty-state">
            <strong>暂无符合条件的会议</strong>
            <span>点击右上「+ 新建会议」开始或调整筛选条件。</span>
          </div>
        ) : null}

        {filtered.length > 0 ? (
          <table className="data-table">
            <thead>
              <tr>
                <th>标题</th>
                <th>状态</th>
                <th>安全等级</th>
                <th>语言</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((m: Meeting) => (
                <tr key={m.meetingId}>
                  <td><Link to={`/meetings/${m.meetingId}`}>{m.title}</Link></td>
                  <td><span className="pill pill--info">{STATUS_LABEL[m.status] ?? m.status}</span></td>
                  <td><span className={`pill ${SECURITY_TONE[m.securityLevel] ?? "pill--neutral"}`}>{m.securityLevel}</span></td>
                  <td>{m.language}</td>
                  <td>{formatDate(m.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </div>
  );
}
```

- [ ] **Step 5: 跑测试通过**

```bash
npm test -- src/features/meetings/__tests__/MeetingListPage.test.tsx 2>&1 | tail
```

Expected: PASS（新增的 + 旧的全过）

- [ ] **Step 6: 提交**

```bash
git add apps/meeting-web/src/features/meetings/queries.ts apps/meeting-web/src/features/meetings/MeetingListPage.tsx apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx
git commit -m "feat(meeting-web): rewrite MeetingListPage with URL filters, useQuery, Iceberg pill"
```

### Task 3.2：MeetingDetailPage 拆为概览 tab + 启动面板

**Files:**
- Create: `apps/meeting-web/src/features/meetings/MeetingOverviewTab.tsx`
- Create: `apps/meeting-web/src/features/meetings/StartTaskPanel.tsx`
- Create: `apps/meeting-web/src/features/meetings/MeetingTabBar.tsx`
- Modify: `apps/meeting-web/src/features/meetings/MeetingDetailPage.tsx`

- [ ] **Step 1: 写 MeetingTabBar**

```tsx
// apps/meeting-web/src/features/meetings/MeetingTabBar.tsx
import { NavLink, useParams } from "react-router-dom";

interface Tab { to: string; label: string; }

export function MeetingTabBar() {
  const { meetingId = "" } = useParams();
  const tabs: Tab[] = [
    { to: `/meetings/${meetingId}`, label: "概览" },
    { to: `/meetings/${meetingId}/transcript`, label: "转录" },
    { to: `/meetings/${meetingId}/minutes`, label: "纪要" },
    { to: `/meetings/${meetingId}/items`, label: "行动项" },
    { to: `/meetings/${meetingId}/speakers`, label: "说话人" },
    { to: `/meetings/${meetingId}/exports`, label: "导出" },
  ];
  return (
    <nav className="tabbar" aria-label="会议导航">
      {tabs.map((t) => (
        <NavLink key={t.to} to={t.to} end={t.to.endsWith(meetingId)}>
          {t.label}
        </NavLink>
      ))}
    </nav>
  );
}
```

- [ ] **Step 2: 写 MeetingOverviewTab**

```tsx
// apps/meeting-web/src/features/meetings/MeetingOverviewTab.tsx
import { Link } from "react-router-dom";
import type { Meeting } from "@shared/api/types";
import { formatDate } from "@shared/utils/formatters";
import { StartTaskPanel } from "./StartTaskPanel";

export function MeetingOverviewTab({ meeting }: { meeting: Meeting }) {
  return (
    <div className="stack">
      <section className="grid">
        <div className="metric">
          <div className="metric__label">状态</div>
          <div className="metric__value">{meeting.status}</div>
        </div>
        <div className="metric">
          <div className="metric__label">安全等级</div>
          <div className="metric__value">{meeting.securityLevel}</div>
        </div>
        <div className="metric">
          <div className="metric__label">语言</div>
          <div className="metric__value">{meeting.language}</div>
        </div>
        <div className="metric">
          <div className="metric__label">转录版本</div>
          <div className="metric__value">v{meeting.transcriptVersion}</div>
        </div>
        <div className="metric">
          <div className="metric__label">纪要版本</div>
          <div className="metric__value">v{meeting.minutesVersion}</div>
        </div>
        <div className="metric">
          <div className="metric__label">创建时间</div>
          <div className="metric__value" style={{ fontSize: 14 }}>{formatDate(meeting.createdAt)}</div>
        </div>
      </section>

      <StartTaskPanel meetingId={meeting.meetingId} />

      <section className="card stack">
        <strong>快速进入</strong>
        <div className="toolbar">
          <Link className="button" to={`/meetings/${meeting.meetingId}/audio`}>上传音频</Link>
          <Link className="button" to={`/meetings/${meeting.meetingId}/transcript`}>转录</Link>
          <Link className="button" to={`/meetings/${meeting.meetingId}/minutes`}>纪要</Link>
          <Link className="button" to={`/meetings/${meeting.meetingId}/exports`}>导出</Link>
        </div>
      </section>
    </div>
  );
}
```

- [ ] **Step 3: 写 StartTaskPanel**

```tsx
// apps/meeting-web/src/features/meetings/StartTaskPanel.tsx
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStartTask } from "./queries";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function StartTaskPanel({ meetingId }: { meetingId: string }) {
  const navigate = useNavigate();
  const [audioFileId, setAudioFileId] = useState("audio_fixture_01");
  const start = useStartTask(meetingId);

  const errorMsg = start.error
    ? ((start.error as ApiClientError).code ? getUserMessage((start.error as ApiClientError).code!) : "启动失败")
    : null;

  async function handleStart() {
    const fileId = audioFileId.trim() || "audio_fixture_01";
    const task = await start.mutateAsync(fileId);
    navigate(`/meetings/${meetingId}/tasks/${task.taskId}`);
  }

  return (
    <section className="card stack">
      <div className="page-header">
        <div>
          <strong>处理任务</strong>
          <p className="page-subtitle">提交音频文件 ID 启动 MEETING_FULL_PIPELINE。</p>
        </div>
      </div>
      {errorMsg ? <div className="banner banner--danger" role="alert"><strong className="banner__title">{errorMsg}</strong></div> : null}
      <div className="field" style={{ maxWidth: 420 }}>
        <label className="field__label" htmlFor="audio-file-id">音频文件 ID</label>
        <input id="audio-file-id" name="audioFileId" autoComplete="off" value={audioFileId} onChange={(e) => setAudioFileId(e.target.value)} />
      </div>
      <button type="button" className="button button--primary" disabled={start.isPending} onClick={handleStart}>
        {start.isPending ? "启动中…" : "启动 MEETING_FULL_PIPELINE"}
      </button>
    </section>
  );
}
```

- [ ] **Step 4: 重写 MeetingDetailPage 使用 OverviewTab**

```tsx
// apps/meeting-web/src/features/meetings/MeetingDetailPage.tsx
import { Link, useParams } from "react-router-dom";
import { useMeetingQuery } from "./queries";
import { MeetingOverviewTab } from "./MeetingOverviewTab";
import { MeetingTabBar } from "./MeetingTabBar";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function MeetingDetailPage() {
  const { meetingId } = useParams();
  const { data: meeting, isPending, error } = useMeetingQuery(meetingId);

  return (
    <div className="page">
      {isPending ? <p className="page-subtitle" aria-busy="true">加载中…</p> : null}
      {error ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">会议加载失败</strong>
          <span className="banner__body">{(error as ApiClientError).code ? getUserMessage((error as ApiClientError).code!) : "请稍后重试"}</span>
        </div>
      ) : null}
      {meeting ? (
        <>
          <header className="page-header">
            <div>
              <h1 className="page-title">{meeting.title}</h1>
              <p className="page-subtitle">
                <span translate="no">{meeting.meetingId}</span> · {meeting.securityLevel} · {meeting.language}
              </p>
            </div>
            <div className="page-actions">
              <Link className="button button--primary" to={`/meetings/${meeting.meetingId}/audio`}>上传音频</Link>
              <Link className="button" to="/meetings">返回列表</Link>
            </div>
          </header>
          <MeetingTabBar />
          <MeetingOverviewTab meeting={meeting} />
        </>
      ) : null}
    </div>
  );
}
```

- [ ] **Step 5: 适配现有测试**

```bash
npm test -- src/features/meetings/__tests__/ 2>&1 | tail -30
```

如果 MeetingDetailPage 测试期望旧文案，更新断言为新结构。具体修改根据 fail message。

- [ ] **Step 6: 提交**

```bash
git add apps/meeting-web/src/features/meetings/
git commit -m "feat(meeting-web): split MeetingDetailPage into overview tab + start task panel + tabbar"
```

### Task 3.3：TaskProgressPage 重写（phase strip + 来源人话化）

**Files:**
- Create: `apps/meeting-web/src/shared/components/PhaseStrip.tsx`
- Create: `apps/meeting-web/src/shared/components/SourceLabel.tsx`
- Modify: `apps/meeting-web/src/features/tasks/queries.ts`
- Modify: `apps/meeting-web/src/features/tasks/TaskProgressPage.tsx`
- Modify: `apps/meeting-web/src/features/tasks/__tests__/TaskProgressPage.test.tsx`

- [ ] **Step 1: 写 PhaseStrip 组件**

```tsx
// apps/meeting-web/src/shared/components/PhaseStrip.tsx
import type { ProcessingTaskPhase } from "@shared/api/types";

const SEGMENTS = [
  { key: "WORKER_DAG", label: "worker DAG" },
  { key: "JAVA_LLM", label: "Java LLM" },
  { key: "TERMINAL", label: "完成" },
] as const;

function stateFor(seg: (typeof SEGMENTS)[number]["key"], phase: ProcessingTaskPhase | null | undefined): "pending" | "active" | "done" {
  if (!phase) return "pending";
  const order: Record<ProcessingTaskPhase, number> = {
    WORKER_DAG_RUNNING: 0,
    WORKER_DAG_DONE: 0,
    JAVA_LLM_RUNNING: 1,
    TERMINAL: 2,
  } as const;
  const segIdx = SEGMENTS.findIndex((s) => s.key === seg);
  const phaseIdx = order[phase];
  if (phaseIdx === undefined) return "pending";
  if (phaseIdx === segIdx) return phase === "WORKER_DAG_DONE" || phase === "TERMINAL" ? "done" : "active";
  if (phaseIdx > segIdx) return "done";
  return "pending";
}

export function PhaseStrip({ phase }: { phase: ProcessingTaskPhase | null | undefined }) {
  return (
    <div>
      <div className="phase-strip" role="progressbar" aria-label="任务阶段进度">
        {SEGMENTS.map((s) => (
          <div key={s.key} className="phase-strip__seg" data-state={stateFor(s.key, phase)} />
        ))}
      </div>
      <div className="toolbar" style={{ marginTop: 4, gap: 16 }}>
        {SEGMENTS.map((s) => (
          <span key={s.key} className="phase-strip__label">{s.label}</span>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 写 SourceLabel**

```tsx
// apps/meeting-web/src/shared/components/SourceLabel.tsx
const MAP: Record<string, string> = {
  AI_WORKER_CALLBACK: "worker 回调",
  JAVA_TASK_SERVICE: "Java 任务服务",
};

export function SourceLabel({ source }: { source: string | null | undefined }) {
  if (!source) return <span className="pill pill--neutral">未知</span>;
  return <span className="pill pill--neutral">{MAP[source] ?? source}</span>;
}
```

- [ ] **Step 3: 写 tasks/queries.ts**

```ts
// apps/meeting-web/src/features/tasks/queries.ts
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { cancelTask, retryTask } from "@shared/api/client";

export function useRetryTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => retryTask(taskId),
    onSuccess: (_, taskId) => qc.invalidateQueries({ queryKey: ["task", taskId] }),
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => cancelTask(taskId),
    onSuccess: (_, taskId) => qc.invalidateQueries({ queryKey: ["task", taskId] }),
  });
}
```

- [ ] **Step 4: 改 TaskProgressPage 测试**

修改 `apps/meeting-web/src/features/tasks/__tests__/TaskProgressPage.test.tsx`，新增/更新断言：

```tsx
it("renders 来源 column with human-readable labels", async () => {
  // ... existing setup ...
  expect(await screen.findByText("worker 回调")).toBeInTheDocument();
});

it("renders phase strip with role progressbar", async () => {
  // ... existing setup ...
  expect(await screen.findByRole("progressbar", { name: /任务阶段进度/ })).toBeInTheDocument();
});

it("shows SSE dot indicator on active connection", async () => {
  // ... existing setup ...
  expect(await screen.findByText("SSE")).toBeInTheDocument();
});
```

- [ ] **Step 5: 重写 TaskProgressPage**

```tsx
// apps/meeting-web/src/features/tasks/TaskProgressPage.tsx
import { useMemo } from "react";
import { Link, useParams } from "react-router-dom";
import { useTaskEventsStream } from "@shared/queries/useTaskEventsStream";
import { useRetryTask, useCancelTask } from "./queries";
import { PhaseStrip } from "@shared/components/PhaseStrip";
import { SourceLabel } from "@shared/components/SourceLabel";
import { getUserMessage } from "@shared/utils/error-mapper";

const STATUS_LABEL: Record<string, string> = {
  PENDING: "等待中",
  QUEUED: "已排队",
  RUNNING: "进行中",
  SUCCEEDED: "已完成",
  PARTIAL_SUCCEEDED: "部分完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  ORPHANED: "已回收",
  CANCEL_PENDING: "取消中",
};

const STATUS_DOT: Record<string, string> = {
  PENDING: "dot",
  QUEUED: "dot dot--info",
  RUNNING: "dot dot--info",
  SUCCEEDED: "dot dot--success",
  PARTIAL_SUCCEEDED: "dot dot--warn",
  FAILED: "dot dot--danger",
  CANCELLED: "dot",
  ORPHANED: "dot dot--danger",
  CANCEL_PENDING: "dot dot--warn",
};

export function TaskProgressPage() {
  const { meetingId = "", taskId = "" } = useParams();
  const { snapshot, connectionMode } = useTaskEventsStream(taskId);
  const retry = useRetryTask();
  const cancel = useCancelTask();

  const totalProgress = useMemo(() => {
    if (snapshot.steps.length === 0) return 0;
    return Math.round(snapshot.steps.reduce((sum, s) => sum + s.progress, 0) / snapshot.steps.length);
  }, [snapshot.steps]);

  const isTerminal = ["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"].includes(snapshot.status ?? "");

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">任务进度</h1>
          <p className="page-subtitle"><span translate="no">{taskId}</span></p>
        </div>
        <div className="page-actions">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <button type="button" className="button" disabled={!snapshot.retryable || retry.isPending} onClick={() => retry.mutate(taskId)}>
            {retry.isPending ? "重试中…" : "重试"}
          </button>
          <button type="button" className="button" disabled={isTerminal || cancel.isPending} onClick={() => cancel.mutate(taskId)}>
            {cancel.isPending ? "取消中…" : "取消"}
          </button>
          <span className="pill" aria-label="连接模式">
            <span className={connectionMode === "SSE" ? "dot dot--success" : connectionMode === "POLLING" ? "dot dot--warn" : "dot"} />
            {connectionMode === "SSE" ? "SSE" : connectionMode === "POLLING" ? "轮询" : "已结束"}
          </span>
        </div>
      </header>

      <section className="grid">
        <div className="metric">
          <div className="metric__label">状态</div>
          <div className="metric__value" style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span className={STATUS_DOT[snapshot.status ?? ""] ?? "dot"} />
            {STATUS_LABEL[snapshot.status ?? ""] ?? "—"}
          </div>
        </div>
        <div className="metric">
          <div className="metric__label">阶段</div>
          <div className="metric__value" style={{ fontSize: 16 }}>{snapshot.phase ?? "—"}</div>
          <PhaseStrip phase={snapshot.phase} />
        </div>
        <div className="metric">
          <div className="metric__label">尝试</div>
          <div className="metric__value">{snapshot.attemptNo}</div>
        </div>
        <div className="metric">
          <div className="metric__label">总体进度</div>
          <div className="metric__value">{totalProgress}%</div>
          <div className="progress"><div className="progress__fill" style={{ width: `${totalProgress}%` }} /></div>
        </div>
      </section>

      {snapshot.lastErrorCode ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">最近错误</strong>
          <span className="banner__body">{getUserMessage(snapshot.lastErrorCode)} · <code translate="no">{snapshot.lastErrorCode}</code></span>
        </div>
      ) : null}

      <section className="card stack">
        <strong>步骤</strong>
        <table className="data-table">
          <thead>
            <tr>
              <th>步骤</th>
              <th>状态</th>
              <th>进度</th>
              <th>来源</th>
              <th className="num">尝试</th>
            </tr>
          </thead>
          <tbody>
            {snapshot.steps.map((step) => (
              <tr key={step.stepName}>
                <td><strong>{step.stepName}</strong>{step.stepName === "AUDIO_UPLOAD" ? <div className="page-subtitle">已完成于任务创建时</div> : null}</td>
                <td><span className={`pill ${step.status === "SUCCEEDED" ? "pill--success" : step.status === "FAILED" ? "pill--danger" : step.status === "RUNNING" ? "pill--info" : "pill--neutral"}`}>{step.status}</span></td>
                <td style={{ minWidth: 160 }}>
                  <div className="progress"><div className="progress__fill" style={{ width: `${step.progress}%` }} /></div>
                  <span className="page-subtitle">{step.progress}%</span>
                </td>
                <td><SourceLabel source={step.source} /></td>
                <td className="num">{step.attemptNo ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
```

- [ ] **Step 6: 跑测试通过**

```bash
npm test -- src/features/tasks/__tests__/TaskProgressPage.test.tsx 2>&1 | tail
```

Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add apps/meeting-web/src/features/tasks/ apps/meeting-web/src/shared/components/PhaseStrip.tsx apps/meeting-web/src/shared/components/SourceLabel.tsx
git commit -m "feat(meeting-web): rewrite TaskProgressPage with phase strip and human-readable source labels"
```

### Task 3.4：TranscriptPage 重写（status banner + reduced motion）

**Files:**
- Modify: `apps/meeting-web/src/features/transcript/queries.ts`
- Modify: `apps/meeting-web/src/features/transcript/TranscriptPage.tsx`
- Modify: `apps/meeting-web/src/app/app.css`（加 .segment-row 及 reduced-motion 替代）

- [ ] **Step 1: 写 transcript queries**

```ts
// apps/meeting-web/src/features/transcript/queries.ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLatestMeetingTask, getTranscript, updateSegment } from "@shared/api/client";

export function useTranscriptQuery(meetingId: string) {
  return useQuery({
    queryKey: ["transcript", meetingId],
    queryFn: () => getTranscript(meetingId),
    enabled: !!meetingId,
    retry: (failureCount, error: any) => error?.status !== 404 && failureCount < 2,
  });
}

export function useLatestMeetingTaskQuery(meetingId: string) {
  return useQuery({
    queryKey: ["meeting-task", meetingId],
    queryFn: () => getLatestMeetingTask(meetingId),
    enabled: !!meetingId,
  });
}

export function useUpdateSegment(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ segmentId, text, version, reason }: { segmentId: string; text: string; version: number; reason: string | null }) =>
      updateSegment(meetingId, segmentId, text, version, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["transcript", meetingId] });
      qc.invalidateQueries({ queryKey: ["minutes", meetingId] });
    },
  });
}
```

- [ ] **Step 2: 加 segment row CSS 与 reduced motion**

追加到 `apps/meeting-web/src/app/app.css`：

```css
.segment-list { display: flex; flex-direction: column; gap: var(--space-3); max-height: 70vh; overflow: auto; padding-right: var(--space-2); }
.segment-row { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-3); border-radius: var(--radius-m); border: 1px solid var(--line-2); transition: background-color 200ms ease; }
.segment-row p { margin: 0; line-height: 1.7; }
.segment-row__meta { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; font-size: 12px; color: var(--ink-3); }
.segment-row__time { font-variant-numeric: tabular-nums; }
.segment-row--highlighted { background: #fff7d6; animation: rag-flash 2.5s ease-out; }

@keyframes rag-flash {
  0% { background: #ffe97a; }
  20% { background: #ffe97a; }
  100% { background: #fff7d6; }
}

@media (prefers-reduced-motion: reduce) {
  .segment-row--highlighted { animation: none; background: #fff7d6; }
}
```

- [ ] **Step 3: 重写 TranscriptPage（保留段编辑逻辑，更新样式与状态横幅）**

```tsx
// apps/meeting-web/src/features/transcript/TranscriptPage.tsx
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useTranscriptQuery, useLatestMeetingTaskQuery, useUpdateSegment } from "./queries";
import type { ApiClientError } from "@shared/api/client";
import type { TranscriptSegment } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";
import { formatMs } from "@shared/utils/formatters";
import { MeetingTabBar } from "@features/meetings/MeetingTabBar";

const STALE_TEXT = "下游纪要、行动项、决策、风险与 RAG 索引已被标记为过期，重新生成后会读取最新转录";

export function TranscriptPage() {
  const { meetingId = "" } = useParams();
  const [params] = useSearchParams();
  const targetSegmentId = params.get("segmentId");
  const targetStartMs = params.get("startMs");

  const { data: transcript, error } = useTranscriptQuery(meetingId);
  const { data: task } = useLatestMeetingTaskQuery(meetingId);
  const update = useUpdateSegment(meetingId);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingText, setEditingText] = useState("");
  const [editingReason, setEditingReason] = useState("");
  const [staleNoticeVisible, setStaleNoticeVisible] = useState(false);
  const [highlightedSegmentId, setHighlightedSegmentId] = useState<string | null>(null);
  const [missingTarget, setMissingTarget] = useState(false);

  const segmentRefs = useRef<Map<string, HTMLElement>>(new Map());

  const sortedSegments = useMemo(
    () => [...(transcript?.segments ?? [])].sort((a, b) => a.startMs - b.startMs),
    [transcript],
  );

  useEffect(() => {
    if (!transcript || (!targetSegmentId && !targetStartMs)) return;
    let match: TranscriptSegment | undefined;
    if (targetSegmentId) match = transcript.segments.find((s) => s.segmentId === targetSegmentId);
    if (!match && targetStartMs) {
      const want = Number.parseInt(targetStartMs, 10);
      if (Number.isFinite(want)) {
        match = transcript.segments.find((s) => s.startMs <= want && s.endMs >= want)
          ?? [...transcript.segments].sort((a, b) => Math.abs(a.startMs - want) - Math.abs(b.startMs - want))[0];
      }
    }
    if (!match) { setMissingTarget(true); return; }
    setMissingTarget(false);
    setHighlightedSegmentId(match.segmentId);
    const node = segmentRefs.current.get(match.segmentId);
    if (node?.scrollIntoView) node.scrollIntoView({ block: "center", behavior: "smooth" });
    const timer = window.setTimeout(() => setHighlightedSegmentId(null), 2500);
    return () => window.clearTimeout(timer);
  }, [transcript, targetSegmentId, targetStartMs]);

  const taskProcessing = task && !["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED"].includes(task.status);
  const taskFailed = task?.status === "FAILED" || task?.status === "ORPHANED";

  const startEdit = useCallback((segment: TranscriptSegment) => {
    setEditingId(segment.segmentId);
    setEditingText(segment.currentText);
    setEditingReason("");
  }, []);

  const cancelEdit = () => { setEditingId(null); setEditingText(""); setEditingReason(""); };

  const saveEdit = async (segment: TranscriptSegment) => {
    if (!transcript) return;
    if (editingText === segment.currentText) { cancelEdit(); return; }
    try {
      await update.mutateAsync({ segmentId: segment.segmentId, text: editingText, version: transcript.transcriptVersion, reason: editingReason || null });
      setStaleNoticeVisible(true);
      cancelEdit();
    } catch {
      /* error 通过 update.error 渲染 */
    }
  };

  const errorMsg = error ? ((error as ApiClientError).code ? getUserMessage((error as ApiClientError).code!) : "转录加载失败") : null;
  const updateError = update.error as ApiClientError | null;
  const updateErrorMsg = updateError?.code === "VERSION_CONFLICT" ? "内容已被更新；已自动刷新到最新版本，请重新编辑" : updateError?.code ? getUserMessage(updateError.code) : null;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">转录</h1>
          <p className="page-subtitle"><span translate="no">{meetingId}</span></p>
        </div>
        <div className="page-actions">
          <Link className="button" to={`/meetings/${meetingId}/audio`}>上传音频</Link>
          {task ? <Link className="button" to={`/meetings/${meetingId}/tasks/${task.taskId}`}>任务进度</Link> : null}
        </div>
      </header>

      <MeetingTabBar />

      {missingTarget ? (
        <div className="banner banner--warn" role="status" aria-live="polite">
          <strong className="banner__title">未找到指定片段</strong>
          <span className="banner__body">引用指向的片段不在当前版本中（可能已被编辑覆盖）。可继续浏览全文。</span>
        </div>
      ) : null}

      {staleNoticeVisible ? (
        <div className="banner banner--warn" role="status" aria-live="polite">
          <strong className="banner__title">已应用编辑</strong>
          <span className="banner__body">{STALE_TEXT}</span>
          <div className="toolbar">
            <Link className="button button--primary" to={`/meetings/${meetingId}/minutes`}>查看纪要</Link>
            <button type="button" className="button button--ghost" onClick={() => setStaleNoticeVisible(false)}>知道了</button>
          </div>
        </div>
      ) : null}

      {taskProcessing && task ? (
        <div className="banner banner--info">
          <strong className="banner__title">处理中</strong>
          <span className="banner__body">状态 {task.status}{task.currentStep ? ` · ${task.currentStep}` : ""}</span>
        </div>
      ) : null}

      {taskFailed && task ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">处理失败</strong>
          <span className="banner__body">{task.lastErrorCode ? getUserMessage(task.lastErrorCode) : ""}</span>
          <Link className="button button--primary" to={`/meetings/${meetingId}/tasks/${task.taskId}`}>查看并重试</Link>
        </div>
      ) : null}

      {errorMsg ? <div className="banner banner--danger" role="alert"><strong className="banner__title">{errorMsg}</strong></div> : null}
      {updateErrorMsg ? <div className="banner banner--danger" role="alert"><strong className="banner__title">{updateErrorMsg}</strong></div> : null}

      <section className="card stack">
        <div className="toolbar">
          <strong>片段</strong>
          {transcript ? <span className="pill pill--info">v{transcript.transcriptVersion}</span> : null}
          <span className="page-subtitle">{sortedSegments.length} 条</span>
        </div>

        {sortedSegments.length === 0 ? (
          <div className="empty-state"><strong>暂无转录内容</strong><span>等待 worker 完成或检查任务进度。</span></div>
        ) : (
          <div className="segment-list">
            {sortedSegments.map((segment) => (
              <article
                key={segment.segmentId}
                className={`segment-row${highlightedSegmentId === segment.segmentId ? " segment-row--highlighted" : ""}`}
                ref={(node) => { if (node) segmentRefs.current.set(segment.segmentId, node); else segmentRefs.current.delete(segment.segmentId); }}
                aria-label={`segment-${segment.segmentId}`}
              >
                <div className="segment-row__meta">
                  <strong>{segment.speakerDisplayName || segment.speakerLabel}</strong>
                  <span className="segment-row__time">{formatMs(segment.startMs)} – {formatMs(segment.endMs)}</span>
                  <span className="pill pill--neutral">{Math.round(segment.asrConfidence * 100)}%</span>
                  {segment.editedText && segment.editedText !== segment.originalText ? <span className="pill pill--warn">已编辑</span> : null}
                </div>

                {editingId === segment.segmentId ? (
                  <div className="stack">
                    <div className="field">
                      <label className="field__label" htmlFor={`segment-edit-${segment.segmentId}`}>编辑片段内容</label>
                      <textarea
                        id={`segment-edit-${segment.segmentId}`}
                        name="segment-edit"
                        value={editingText}
                        onChange={(e) => setEditingText(e.target.value)}
                        rows={3}
                      />
                    </div>
                    <div className="field">
                      <label className="field__label" htmlFor={`segment-reason-${segment.segmentId}`}>编辑原因（可选）</label>
                      <input
                        id={`segment-reason-${segment.segmentId}`}
                        name="segment-reason"
                        placeholder="例如：修正错听人名…"
                        value={editingReason}
                        onChange={(e) => setEditingReason(e.target.value)}
                      />
                    </div>
                    <div className="toolbar">
                      <button type="button" className="button button--primary" disabled={update.isPending} onClick={() => saveEdit(segment)}>
                        {update.isPending ? "保存中…" : "保存"}
                      </button>
                      <button type="button" className="button button--ghost" disabled={update.isPending} onClick={cancelEdit}>取消</button>
                      {segment.editedText ? <span className="page-subtitle">原文：{segment.originalText}</span> : null}
                    </div>
                  </div>
                ) : (
                  <div className="stack">
                    <p>{segment.currentText}</p>
                    <div className="toolbar">
                      <button type="button" className="button button--ghost" onClick={() => startEdit(segment)}>编辑</button>
                    </div>
                  </div>
                )}
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
```

- [ ] **Step 4: 跑 TranscriptPage 测试**

```bash
npm test -- src/features/transcript/__tests__/TranscriptPage.test.tsx 2>&1 | tail
```

适配现有断言到新结构。

- [ ] **Step 5: 提交**

```bash
git add apps/meeting-web/src/features/transcript/ apps/meeting-web/src/app/app.css
git commit -m "feat(meeting-web): rewrite TranscriptPage with status banners, segment-row, reduced motion"
```

### Task 3.5：RagPage 重写

**Files:**
- Create: `apps/meeting-web/src/features/rag/RagQueryPanel.tsx`
- Create: `apps/meeting-web/src/features/rag/RagAnswerPanel.tsx`
- Modify: `apps/meeting-web/src/features/rag/queries.ts`
- Modify: `apps/meeting-web/src/features/rag/RagPage.tsx`
- Modify: `apps/meeting-web/src/app/app.css`（加 .rag-layout）

- [ ] **Step 1: 加 rag-layout CSS**

```css
/* 追加到 app.css */
.rag-layout { display: grid; grid-template-columns: 1fr 1.5fr; gap: var(--space-5); }
@media (max-width: 1024px) { .rag-layout { grid-template-columns: 1fr; } }
.rag-citation-card { background: var(--surface-raised); border: 1px solid var(--line-1); border-radius: var(--radius-m); padding: var(--space-3); }
.rag-citation-card blockquote { margin: 0; padding: 0; color: var(--ink-2); }
```

- [ ] **Step 2: 写 rag queries**

```ts
// apps/meeting-web/src/features/rag/queries.ts
import { useMutation } from "@tanstack/react-query";
import { listDocuments, listMeetings, ragQuery } from "@shared/api/client";
import { useQuery } from "@tanstack/react-query";

export function useRagScopeQuery() {
  return useQuery({
    queryKey: ["rag", "scope"],
    queryFn: async () => {
      const [meetings, documents] = await Promise.all([listMeetings(), listDocuments()]);
      return { meetings: meetings.items, documents: documents.items };
    },
  });
}

export function useRagAsk() {
  return useMutation({ mutationFn: ragQuery });
}
```

- [ ] **Step 3: 写 RagQueryPanel + RagAnswerPanel + RagPage**

由于代码量大，按以下结构分别写入（保留原 RagPage 业务逻辑、仅做拆分 + 样式）：

```tsx
// apps/meeting-web/src/features/rag/RagPage.tsx
import { useState } from "react";
import { useRagAsk, useRagScopeQuery } from "./queries";
import { RagQueryPanel } from "./RagQueryPanel";
import { RagAnswerPanel } from "./RagAnswerPanel";
import type { RagAnswerDTO } from "@shared/api/types";

export function RagPage() {
  const scope = useRagScopeQuery();
  const ask = useRagAsk();
  const [answer, setAnswer] = useState<RagAnswerDTO | null>(null);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">问答</h1>
          <p className="page-subtitle">基于已索引的会议转写与文档生成带引用的回答。所有候选片段经过 Java 端二次权限校验。</p>
        </div>
      </header>

      <div className="rag-layout">
        <RagQueryPanel
          meetings={scope.data?.meetings ?? []}
          documents={scope.data?.documents ?? []}
          loadError={scope.error}
          pending={ask.isPending}
          askError={ask.error}
          onAsk={async (req) => {
            const result = await ask.mutateAsync(req);
            setAnswer(result);
          }}
        />
        <RagAnswerPanel answer={answer} />
      </div>
    </div>
  );
}
```

`RagQueryPanel.tsx` 和 `RagAnswerPanel.tsx` 从现有 RagPage.tsx 的内联组件迁移而来，把 `aria-label="rag-question-input"` 改为 `<label htmlFor>`。完整文件参见 spec §5。每个文件 < 200 行。

由于篇幅，本步骤的完整代码在执行时按 spec 行动项 + 现有 RagPage.tsx 拆分实现。

- [ ] **Step 4: 跑 RagPage 测试**

```bash
npm test -- src/features/rag/__tests__/RagPage.test.tsx 2>&1 | tail
```

适配测试断言到新结构（label、双列布局可能不影响测试）。

- [ ] **Step 5: 提交**

```bash
git add apps/meeting-web/src/features/rag/ apps/meeting-web/src/app/app.css
git commit -m "feat(meeting-web): split RagPage into query/answer panels with proper labels and 2-column layout"
```

### Task 3.6：Phase 3 闸门

- [ ] **Step 1: 跑 meeting-web 全量测试 + build**

```bash
cd apps/meeting-web && npm test && npm run build
```

Expected: 全绿。任何剩余未改造页面（minutes/items/exports/audio/speakers/admin/documents）的测试仍能通过（因为外壳变了但 inner 没动）。

---

## Phase 4：合规与长尾页

目标：Minutes / Items / Documents / Exports / SpeakerConfirm / 合规四页全部按 Iceberg 系统重写，「应急访问」文案落地。

### Task 4.1：MinutesPage

**Files:**
- Modify: `apps/meeting-web/src/features/minutes/queries.ts`
- Modify: `apps/meeting-web/src/features/minutes/MinutesPage.tsx`

- [ ] **Step 1: 写 minutes queries**

```ts
// apps/meeting-web/src/features/minutes/queries.ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getMeeting, getMinutes, regenerateMinutes } from "@shared/api/client";

export function useMinutesQuery(meetingId: string) {
  return useQuery({
    queryKey: ["minutes", meetingId],
    queryFn: () => getMinutes(meetingId),
    enabled: !!meetingId,
    retry: (count, err: any) => err?.status !== 404 && err?.code !== "SECURITY_LEVEL_BLOCKED" && count < 2,
  });
}

export function useRegenerateMinutes(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (transcriptVersion: number) => regenerateMinutes(meetingId, transcriptVersion),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["minutes", meetingId] }),
  });
}

export function useMeetingForMinutes(meetingId: string) {
  return useQuery({ queryKey: ["meeting", meetingId], queryFn: () => getMeeting(meetingId), enabled: !!meetingId });
}
```

- [ ] **Step 2: 重写 MinutesPage**

参照现有 MinutesPage 的业务流程，替换为：

```tsx
// apps/meeting-web/src/features/minutes/MinutesPage.tsx
import { useParams, Link } from "react-router-dom";
import { useMinutesQuery, useMeetingForMinutes, useRegenerateMinutes } from "./queries";
import { SecurityLevelBlockedNotice } from "@shared/components/SecurityLevelBlockedNotice";
import { SafeMarkdown } from "@shared/components/SafeMarkdown";
import { MeetingTabBar } from "@features/meetings/MeetingTabBar";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function MinutesPage() {
  const { meetingId = "" } = useParams();
  const { data: meeting } = useMeetingForMinutes(meetingId);
  const { data: minutes, error } = useMinutesQuery(meetingId);
  const regen = useRegenerateMinutes(meetingId);

  const blocked = (error as ApiClientError | null)?.code === "SECURITY_LEVEL_BLOCKED";
  const notFound = (error as ApiClientError | null)?.status === 404;
  const otherError = error && !blocked && !notFound;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">纪要</h1>
          <p className="page-subtitle"><span translate="no">{meetingId}</span>{meeting ? ` · v${meeting.minutesVersion}` : ""}</p>
        </div>
        <div className="page-actions">
          {meeting && !blocked ? (
            <button
              className="button button--primary"
              disabled={regen.isPending}
              onClick={() => regen.mutate(meeting.transcriptVersion)}
            >
              {regen.isPending ? "生成中…" : "重新生成"}
            </button>
          ) : null}
        </div>
      </header>

      <MeetingTabBar />

      {blocked ? <SecurityLevelBlockedNotice /> : null}
      {otherError ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">纪要加载失败</strong>
          <span className="banner__body">{(error as ApiClientError).code ? getUserMessage((error as ApiClientError).code!) : "请稍后重试"}</span>
        </div>
      ) : null}
      {notFound ? (
        <div className="banner banner--info">
          <strong className="banner__title">尚未生成纪要</strong>
          <span className="banner__body">完成转录后点击「重新生成」开始。</span>
        </div>
      ) : null}

      {minutes?.staleStatus === "STALE" ? (
        <div className="banner banner--warn">
          <strong className="banner__title">纪要已过期</strong>
          <span className="banner__body">转录已被编辑（v{minutes.transcriptVersion} → 当前 v{meeting?.transcriptVersion}），此页基于旧版本。</span>
        </div>
      ) : null}

      {minutes && !blocked ? (
        <article className="card stack">
          <SafeMarkdown source={minutes.markdown} />
        </article>
      ) : null}
    </div>
  );
}
```

- [ ] **Step 3: 跑测试**

```bash
npm test -- src/features/minutes/__tests__/MinutesPage.test.tsx 2>&1 | tail
```

适配断言。

- [ ] **Step 4: 提交**

```bash
git add apps/meeting-web/src/features/minutes/
git commit -m "feat(meeting-web): rewrite MinutesPage with banners and Iceberg system"
```

### Task 4.2：ItemsPage（acceptanceStatus 4 色 + NEEDS_REVIEW diff）

**Files:**
- Modify: `apps/meeting-web/src/features/items/queries.ts`
- Modify: `apps/meeting-web/src/features/items/ItemsPage.tsx`
- Modify: `apps/meeting-web/src/app/app.css`（加 .item-row 颜色变体）

- [ ] **Step 1: 写 items queries**

```ts
// apps/meeting-web/src/features/items/queries.ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { acceptItem, listActionItems, listDecisions, listRisks, rejectItem, type ItemKind } from "@shared/api/client";

export function useItemsQuery(meetingId: string) {
  return useQuery({
    queryKey: ["items", meetingId],
    queryFn: async () => {
      const [actions, decisions, risks] = await Promise.all([
        listActionItems(meetingId),
        listDecisions(meetingId),
        listRisks(meetingId),
      ]);
      return { actions: actions.items, decisions: decisions.items, risks: risks.items };
    },
    enabled: !!meetingId,
  });
}

export function useAcceptItem(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ kind, itemId }: { kind: ItemKind; itemId: string }) => acceptItem(meetingId, kind, itemId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["items", meetingId] }),
  });
}

export function useRejectItem(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ kind, itemId }: { kind: ItemKind; itemId: string }) => rejectItem(meetingId, kind, itemId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["items", meetingId] }),
  });
}
```

- [ ] **Step 2: 加 .item-row CSS**

```css
/* 追加到 app.css */
.item-row { background: var(--surface-raised); border: 1px solid var(--line-1); border-left: 3px solid var(--ink-4); border-radius: var(--radius-m); padding: var(--space-3); }
.item-row[data-status="DRAFT"] { border-left-color: var(--accent); }
.item-row[data-status="ACCEPTED"] { border-left-color: var(--success); }
.item-row[data-status="REJECTED"] { border-left-color: var(--danger); opacity: 0.7; }
.item-row[data-status="REJECTED"] .item-row__body { text-decoration: line-through; }
.item-row[data-status="NEEDS_REVIEW"] { border-left-color: var(--warn); }
.item-diff { background: var(--surface-sunken); padding: var(--space-2); border-radius: var(--radius-s); font-size: 12px; }
.item-diff__old { color: var(--danger-ink); text-decoration: line-through; }
.item-diff__new { color: var(--success-ink); background: var(--success-soft); padding: 0 4px; border-radius: var(--radius-s); }
```

- [ ] **Step 3: 重写 ItemsPage**

完整重写参考现有 ItemsPage 的业务逻辑，三段 `<section>` 渲染 actions/decisions/risks，每段 map 出 `<article className="item-row" data-status={item.acceptanceStatus}>`。NEEDS_REVIEW 显示 `<div className="item-diff">` 中老值删除线 + 新值高亮。多选批量按钮放每段顶部。代码 ~ 280 行，按 spec §5 + 现有 ItemsPage 业务字段实现。

- [ ] **Step 4: 跑测试 + 提交**

```bash
npm test -- src/features/items/__tests__/ItemsPage.test.tsx 2>&1 | tail
git add apps/meeting-web/src/features/items/ apps/meeting-web/src/app/app.css
git commit -m "feat(meeting-web): rewrite ItemsPage with acceptance status colors and NEEDS_REVIEW diff"
```

### Task 4.3：DocumentsPage / ExportsPage / MeetingSpeakerConfirmPage

**Files:** 各自 queries.ts + page.tsx

- [ ] **Step 1: 写 DocumentsPage queries + 页面**

写 useDocumentsQuery；页面用 `.data-table` + 索引状态 pill（ACTIVE/STALE/INDEXING）。

- [ ] **Step 2: 写 ExportsPage queries + 页面**

写 useExportsQuery + useCreateExport + useExportEventsStream（SSE 桥 hook，结构同 useTaskEventsStream），ExportsPage 用 `.data-table` + 创建表单卡片，SSE 状态用 connection mode pill。

- [ ] **Step 3: 写 MeetingSpeakerConfirmPage queries + 页面**

useSpeakerCandidatesQuery + useConfirmSpeaker，候选人 grid `.grid`，每张卡显示 label / displayName / verificationStatus / candidate buttons 带置信度。

- [ ] **Step 4: 跑各自测试 + 提交**

```bash
npm test -- src/features/documents/ src/features/exports/ src/features/speakers/__tests__/MeetingSpeakerConfirmPage.test.tsx
git add apps/meeting-web/src/features/documents/ apps/meeting-web/src/features/exports/ apps/meeting-web/src/features/speakers/MeetingSpeakerConfirmPage.tsx apps/meeting-web/src/features/speakers/queries.ts
git commit -m "feat(meeting-web): rewrite documents, exports, speaker confirm pages"
```

### Task 4.4：合规四页（应急访问文案）

**Files:** `apps/meeting-web/src/features/admin/{LegalHoldsPage,DeletionJobsPage,BreakGlassPage,AuditEventsPage}.tsx` + queries.ts

- [ ] **Step 1: 写 admin queries**

```ts
// apps/meeting-web/src/features/admin/queries.ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createLegalHold, listLegalHolds, releaseLegalHold,
  // 其余 deletion / break-glass / audit endpoints
} from "@shared/api/client";

export function useLegalHoldsQuery() {
  return useQuery({ queryKey: ["legalHolds"], queryFn: () => listLegalHolds() });
}

export function useCreateLegalHold() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: createLegalHold,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["legalHolds"] }),
  });
}

export function useReleaseLegalHold() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: releaseLegalHold,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["legalHolds"] }),
  });
}

// 同理写 useDeletionJobsQuery, useBreakGlassRequestsQuery, useAuditEventsQuery，按 client.ts 现有 API
```

- [ ] **Step 2: 重写 LegalHoldsPage**

替换为 `.data-table` 主体 + 创建表单走 `<details>` 折叠或 `Drawer` 组件。状态 pill ACTIVE/RELEASED 上色。释放按钮保留 confirm modal。formatTimestamp 改用 `formatDate`。

- [ ] **Step 3: 重写 DeletionJobsPage**（同 LegalHoldsPage 模式）

- [ ] **Step 4: 重写 BreakGlassPage — UI 文案改「应急访问」**

```tsx
// 关键改动：
<h1 className="page-title">应急访问</h1>
<p className="page-subtitle">紧急情况下的临时授权访问。所有操作进入审计。</p>

// 删除所有 "破玻璃" 字样
```

测试中加 `expect(screen.queryByText("破玻璃")).not.toBeInTheDocument()`。

- [ ] **Step 5: 重写 AuditEventsPage**

包含 URL 同步过滤（事件类型、时间范围）。

- [ ] **Step 6: 跑测试 + 提交**

```bash
npm test -- src/features/admin/
git add apps/meeting-web/src/features/admin/
git commit -m "feat(meeting-web): rewrite admin pages with data-table pattern and 应急访问 wording"
```

### Task 4.5：Phase 4 闸门

```bash
cd apps/meeting-web && npm test && npm run build
```

Expected: 全绿。

---

## Phase 5：三大页面拆分重写

### Task 5.1：SpeakerProfilesPage 拆分

**Files:**
- Create: `apps/meeting-web/src/features/speakers/SpeakerProfileList.tsx`
- Create: `apps/meeting-web/src/features/speakers/SpeakerProfileDetail.tsx`
- Create: `apps/meeting-web/src/features/speakers/SpeakerEnrollPanel.tsx`
- Create: `apps/meeting-web/src/features/speakers/SpeakerSampleUpload.tsx`
- Modify: `apps/meeting-web/src/features/speakers/SpeakerProfilesPage.tsx`
- Modify: `apps/meeting-web/src/features/speakers/queries.ts`

- [ ] **Step 1: 读现有 603 行 SpeakerProfilesPage 全文**

```bash
cat apps/meeting-web/src/features/speakers/SpeakerProfilesPage.tsx
```

记录所有用到的 endpoint / state / 内联样式。

- [ ] **Step 2: 抽取 useSpeakerProfilesQuery / useEnrollMutation 到 queries.ts**

- [ ] **Step 3: 拆为 5 个文件**

每文件 < 200 行，按 spec §5 拆分。内联样式全部转 class（`.audio-recorder`、`.upload-dropzone`、`.sample-text-card` 等加到 app.css）。

- [ ] **Step 4: 跑测试 + 提交**

```bash
npm test -- src/features/speakers/__tests__/SpeakerProfilesPage.test.tsx
git add apps/meeting-web/src/features/speakers/ apps/meeting-web/src/app/app.css
git commit -m "refactor(meeting-web): split SpeakerProfilesPage into 5 focused files, remove inline styles"
```

### Task 5.2：AudioUploadPage 拆分

**Files:**
- Create: `apps/meeting-web/src/features/audio/AudioUploadIntro.tsx`
- Create: `apps/meeting-web/src/features/audio/AudioPartList.tsx`
- Create: `apps/meeting-web/src/features/audio/AudioUploadSummary.tsx`
- Modify: `apps/meeting-web/src/features/audio/AudioUploadPage.tsx`

- [ ] **Step 1: 读 386 行原文**

- [ ] **Step 2: 拆分**

`upload-reducer.ts` 不动。AudioUploadPage 作为控制器；三个子组件按 spec §5 职责拆分；进度条用 `.progress`，分片网格用 `.grid` + `.metric`。

- [ ] **Step 3: 跑测试 + 提交**

```bash
npm test -- src/features/audio/__tests__/
git add apps/meeting-web/src/features/audio/
git commit -m "refactor(meeting-web): split AudioUploadPage into intro/part-list/summary components"
```

### Task 5.3：MeetingWorkstationPage 拆分（ai-worker-web）

**Files:**
- Create: `apps/ai-worker-web/src/pages/workstation/WorkstationShell.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/WizardRail.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/StepCanvas.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/MetaStep.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/AudioStep.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/GlossaryStep.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/DocumentsStep.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/ProcessStep.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/SpeakersStep.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/FinalizeStep.tsx`
- Create: `apps/ai-worker-web/src/pages/workstation/ExportStep.tsx`
- Create: `apps/ai-worker-web/src/features/wizard/store.ts`
- Modify: `apps/ai-worker-web/src/pages/MeetingWorkstationPage.tsx`

- [ ] **Step 1: 写 wizard store**

```ts
// apps/ai-worker-web/src/features/wizard/store.ts
import { create } from "zustand";

export type WizardStep = "META" | "AUDIO" | "GLOSSARY" | "DOCUMENTS" | "PROCESS" | "SPEAKERS" | "FINALIZE" | "EXPORT";

export const STEP_ORDER: WizardStep[] = ["META", "AUDIO", "GLOSSARY", "DOCUMENTS", "PROCESS", "SPEAKERS", "FINALIZE", "EXPORT"];

interface WizardState {
  meetingId: string | null;
  step: WizardStep;
  startedProcessing: boolean;
  finalized: boolean;
  exportId: string | null;
  downloadUrl: string | null;
  setMeetingId: (id: string | null) => void;
  setStep: (step: WizardStep) => void;
  goNext: () => void;
  patch: (p: Partial<Omit<WizardState, "setMeetingId" | "setStep" | "goNext" | "patch" | "reset">>) => void;
  reset: (initial?: Partial<{ meetingId: string | null; step: WizardStep }>) => void;
}

export const useWizardStore = create<WizardState>((set, get) => ({
  meetingId: null,
  step: "META",
  startedProcessing: false,
  finalized: false,
  exportId: null,
  downloadUrl: null,
  setMeetingId: (id) => set({ meetingId: id }),
  setStep: (step) => set({ step }),
  goNext: () => {
    const idx = STEP_ORDER.indexOf(get().step);
    if (idx < STEP_ORDER.length - 1) set({ step: STEP_ORDER[idx + 1]! });
  },
  patch: (p) => set(p),
  reset: (initial) => set({
    meetingId: initial?.meetingId ?? null,
    step: initial?.step ?? (initial?.meetingId ? "AUDIO" : "META"),
    startedProcessing: false,
    finalized: false,
    exportId: null,
    downloadUrl: null,
  }),
}));
```

- [ ] **Step 2: 写 ai-worker-web queries.ts**

```ts
// apps/ai-worker-web/src/features/meetings/queries.ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  attachMeetingDocument, confirmSpeaker, createExport, createMeeting,
  finalizeMeeting, getMeetingAggregate, pollExport, searchDocuments,
  startMeetingProcessing, updateMeetingGlossary,
} from "@/shared/api/endpoints";

export function useMeetingAggregateQuery(meetingId: string | null) {
  return useQuery({
    queryKey: ["aggregate", meetingId],
    queryFn: () => getMeetingAggregate(meetingId!),
    enabled: !!meetingId,
  });
}

export function useCreateMeeting() {
  return useMutation({ mutationFn: createMeeting });
}

export function useStartProcessing() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => startMeetingProcessing(id),
    onSuccess: (_, id) => qc.invalidateQueries({ queryKey: ["aggregate", id] }),
  });
}

// 同理写 useFinalize, useUpdateGlossary, useAttachDocument, useConfirmSpeaker, useCreateExport, useExportPoll
```

- [ ] **Step 3: 写 WorkstationShell + WizardRail + StepCanvas**

```tsx
// apps/ai-worker-web/src/pages/workstation/WorkstationShell.tsx
import { ReactNode } from "react";
import { WizardRail } from "./WizardRail";

export function WorkstationShell({ children }: { children: ReactNode }) {
  return (
    <div className="workstation">
      <aside className="workstation__rail" aria-label="工作流">
        <WizardRail />
      </aside>
      <div className="workstation__canvas">{children}</div>
    </div>
  );
}
```

```tsx
// apps/ai-worker-web/src/pages/workstation/WizardRail.tsx
import { useWizardStore, STEP_ORDER, type WizardStep } from "@/features/wizard/store";
import { useMeetingAggregateQuery } from "@/features/meetings/queries";

const GROUPS: Array<{ title: string; steps: WizardStep[] }> = [
  { title: "准备", steps: ["META", "AUDIO", "GLOSSARY", "DOCUMENTS"] },
  { title: "worker 处理", steps: ["PROCESS", "SPEAKERS"] },
  { title: "Java 收尾", steps: ["FINALIZE", "EXPORT"] },
];

const LABELS: Record<WizardStep, string> = {
  META: "1 建会议",
  AUDIO: "2 上传录音",
  GLOSSARY: "3a 术语",
  DOCUMENTS: "3b 文档",
  PROCESS: "4 处理",
  SPEAKERS: "5 认人",
  FINALIZE: "6a 纪要",
  EXPORT: "6c 导出",
};

function stateFor(step: WizardStep, current: WizardStep, meetingId: string | null): "completed" | "current" | "pending" | "unreachable" {
  const idx = STEP_ORDER.indexOf(step);
  const cur = STEP_ORDER.indexOf(current);
  if (step === current) return "current";
  if (idx < cur) return "completed";
  if (step !== "META" && !meetingId) return "unreachable";
  return "pending";
}

export function WizardRail() {
  const { step, meetingId } = useWizardStore();
  const aggregateQ = useMeetingAggregateQuery(meetingId);
  const workerPhase = aggregateQ.data?.processingTask?.data?.phase ?? null;

  return (
    <>
      {GROUPS.map((g) => (
        <div key={g.title} className="wizard__group">
          <h4>{g.title}</h4>
          {g.steps.map((s) => (
            <div key={s} className="wizard__step" data-state={stateFor(s, step, meetingId)}>
              <span>{LABELS[s]}</span>
              {stateFor(s, step, meetingId) === "completed" ? <span>✓</span> : null}
            </div>
          ))}
        </div>
      ))}
      <div className="wizard__backend-summary">
        <div>worker · {workerPhase ? (workerPhase.startsWith("WORKER_DAG_RUNNING") ? "运行中" : workerPhase === "WORKER_DAG_DONE" ? "已完成" : "—") : "—"}</div>
        <div>Java · {workerPhase === "JAVA_LLM_RUNNING" ? "运行中" : workerPhase === "TERMINAL" ? "完成" : "等待中"}</div>
      </div>
    </>
  );
}
```

```tsx
// apps/ai-worker-web/src/pages/workstation/StepCanvas.tsx
import { useWizardStore } from "@/features/wizard/store";
import { MetaStep } from "./MetaStep";
import { AudioStep } from "./AudioStep";
import { GlossaryStep } from "./GlossaryStep";
import { DocumentsStep } from "./DocumentsStep";
import { ProcessStep } from "./ProcessStep";
import { SpeakersStep } from "./SpeakersStep";
import { FinalizeStep } from "./FinalizeStep";
import { ExportStep } from "./ExportStep";

export function StepCanvas() {
  const step = useWizardStore((s) => s.step);
  switch (step) {
    case "META": return <MetaStep />;
    case "AUDIO": return <AudioStep />;
    case "GLOSSARY": return <GlossaryStep />;
    case "DOCUMENTS": return <DocumentsStep />;
    case "PROCESS": return <ProcessStep />;
    case "SPEAKERS": return <SpeakersStep />;
    case "FINALIZE": return <FinalizeStep />;
    case "EXPORT": return <ExportStep />;
  }
}
```

- [ ] **Step 4: 写每个 *Step.tsx**

逐文件迁移现有 `MeetingWorkstationPage.tsx` 对应 `step === "X"` 块的 JSX。每文件读 store 拿状态、调对应 query / mutation。例：

```tsx
// apps/ai-worker-web/src/pages/workstation/MetaStep.tsx
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useWizardStore } from "@/features/wizard/store";
import { useCreateMeeting } from "@/features/meetings/queries";
import { ApiError } from "@/shared/api/client";

export function MetaStep() {
  const navigate = useNavigate();
  const { patch } = useWizardStore();
  const create = useCreateMeeting();
  const [title, setTitle] = useState("");
  const [securityLevel, setSecurityLevel] = useState<"PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "SECRET">("INTERNAL");

  async function submit() {
    if (!title.trim()) return;
    const meeting = await create.mutateAsync({ title, securityLevel, language: "zh", participants: [] });
    patch({ meetingId: meeting.meetingId });
    navigate(`/meetings/${meeting.meetingId}`, { replace: true });
  }

  return (
    <section className="card stack" aria-labelledby="step-meta">
      <h2 id="step-meta">1 · 建会议</h2>
      <div className="field">
        <label className="field__label" htmlFor="meeting-title">标题</label>
        <input id="meeting-title" name="title" className="input" value={title} onChange={(e) => setTitle(e.target.value)} autoComplete="off" />
      </div>
      <div className="field">
        <label className="field__label" htmlFor="meeting-security">安全级别</label>
        <select id="meeting-security" name="securityLevel" className="select" value={securityLevel} onChange={(e) => setSecurityLevel(e.target.value as typeof securityLevel)}>
          <option value="PUBLIC">PUBLIC</option>
          <option value="INTERNAL">INTERNAL</option>
          <option value="CONFIDENTIAL">CONFIDENTIAL</option>
          <option value="SECRET">SECRET</option>
        </select>
      </div>
      <button className="button button--primary" disabled={create.isPending || !title.trim()} onClick={submit}>
        {create.isPending ? "创建中…" : "下一步：上传录音"}
      </button>
      {create.error ? (
        <div className="error" role="alert">{create.error instanceof ApiError ? `${create.error.error.code}: ${create.error.error.message}` : "创建失败"}</div>
      ) : null}
    </section>
  );
}
```

其余 step 同模式实现。SpeakersStep / FinalizeStep 用 `useMeetingAggregateQuery`，ExportStep 用 `useExportPoll` 替代手写 setInterval。

- [ ] **Step 5: 简化 MeetingWorkstationPage**

```tsx
// apps/ai-worker-web/src/pages/MeetingWorkstationPage.tsx
import { useEffect } from "react";
import { useParams } from "react-router-dom";
import { useWizardStore } from "@/features/wizard/store";
import { WorkstationShell } from "./workstation/WorkstationShell";
import { StepCanvas } from "./workstation/StepCanvas";

export function MeetingWorkstationPage() {
  const params = useParams<{ meetingId?: string }>();
  const routeMeetingId = params.meetingId && params.meetingId !== "new" ? params.meetingId : null;
  const reset = useWizardStore((s) => s.reset);

  useEffect(() => {
    reset({ meetingId: routeMeetingId });
  }, [routeMeetingId, reset]);

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">会议工作站</h1>
          <p className="page-subtitle">{routeMeetingId ?? "新建会议"}</p>
        </div>
      </header>
      <WorkstationShell>
        <StepCanvas />
      </WorkstationShell>
    </div>
  );
}
```

- [ ] **Step 6: 跑测试 + 提交**

```bash
cd apps/ai-worker-web
npm test 2>&1 | tail -30
```

适配 useWizard.test.ts（如果它的导入还指向 `useWizard.ts`，更新或迁到 `useWizardStore.test.ts`）。

```bash
git add apps/ai-worker-web/src/pages/workstation/ apps/ai-worker-web/src/features/wizard/ apps/ai-worker-web/src/pages/MeetingWorkstationPage.tsx apps/ai-worker-web/src/features/meetings/queries.ts
git commit -m "refactor(ai-worker-web): split MeetingWorkstationPage into shell+rail+8 step files with zustand store"
```

### Task 5.4：Phase 5 闸门

```bash
cd apps/meeting-web && npm test && npm run build
cd ../ai-worker-web && npm test && npm run build
```

---

## Phase 6：admin BFF + ai-worker-web 收尾

### Task 6.1：contracts 加 GET /admin/meetings

**Files:**
- Modify: `packages/meeting-contracts/openapi/admin-bff.yaml`
- Create: `packages/meeting-contracts/fixtures/admin-bff/list-meetings.json`

- [ ] **Step 1: 编辑 admin-bff.yaml**

在 `paths:` 下加 `/admin/meetings`：

```yaml
paths:
  /admin/meetings:
    get:
      operationId: listAdminMeetings
      summary: List meetings visible to operator
      security:
        - bearerAuth: []
      parameters:
        - name: limit
          in: query
          schema: { type: integer, minimum: 1, maximum: 100, default: 50 }
        - name: cursor
          in: query
          schema: { type: string }
      responses:
        "200":
          description: List of meetings
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AdminMeetingListResponse'

components:
  schemas:
    AdminMeetingListResponse:
      type: object
      required: [items, nextCursor]
      properties:
        items:
          type: array
          items: { $ref: '#/components/schemas/AdminMeetingSummary' }
        nextCursor:
          type: string
          nullable: true
    AdminMeetingSummary:
      type: object
      required: [meetingId, title, securityLevel, createdAt, language, status]
      properties:
        meetingId: { type: string }
        title: { type: string }
        securityLevel: { type: string, enum: [PUBLIC, INTERNAL, CONFIDENTIAL, SECRET] }
        createdAt: { type: string, format: date-time }
        language: { type: string }
        status: { type: string }
        lastTaskId: { type: string, nullable: true }
        lastTaskStatus: { type: string, nullable: true }
```

- [ ] **Step 2: 写 fixture**

```json
{
  "items": [
    {
      "meetingId": "mtg_01",
      "title": "Q1 季度复盘",
      "securityLevel": "INTERNAL",
      "createdAt": "2026-05-26T10:00:00Z",
      "language": "zh",
      "status": "READY",
      "lastTaskId": "task_01",
      "lastTaskStatus": "SUCCEEDED"
    }
  ],
  "nextCursor": null
}
```

- [ ] **Step 3: 跑 contracts check**

```bash
cd packages/meeting-contracts
npm run check 2>&1 | tail
```

Expected: PASS

- [ ] **Step 4: 跑 codegen**

```bash
npm run codegen
git status
```

Expected: 生成文件可能有 `.gen.ts` / Java DTO 增量。`git diff` 看变更面。

- [ ] **Step 5: 提交**

```bash
cd ../..
git add packages/meeting-contracts/
git commit -m "feat(contracts): add GET /admin/meetings to admin-bff"
```

### Task 6.2：Java 加 admin meetings controller

**Files:**
- Create: `apps/meeting-api/meeting-api-app/src/main/java/.../app/admin/ListAdminMeetingsUseCase.java`
- Create: `apps/meeting-api/meeting-api-adapter/src/main/java/.../adapter/admin/MeetingAdminController.java`
- Create: 对应测试

- [ ] **Step 1: 找到 admin controller 包路径**

```bash
find apps/meeting-api -type d -name "admin"
```

确定包结构。

- [ ] **Step 2: 写 ListAdminMeetingsUseCase**

```java
// apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/admin/ListAdminMeetingsUseCase.java
package com.meeting.api.app.admin;

// imports

@Service
@Transactional(readOnly = true)
public class ListAdminMeetingsUseCase {
  private final MeetingRepository meetings;
  public ListAdminMeetingsUseCase(MeetingRepository meetings) { this.meetings = meetings; }
  public AdminMeetingListView execute(int limit, String cursor) {
    var page = meetings.listForAdmin(limit, cursor);
    return new AdminMeetingListView(page.items(), page.nextCursor());
  }
}
```

如果 `MeetingRepository` 没有 `listForAdmin`，加方法 + MyBatis-Plus 查询实现。

- [ ] **Step 3: 写 MeetingAdminController.list 方法**

```java
// apps/meeting-api/meeting-api-adapter/src/main/java/.../adapter/admin/MeetingAdminController.java
@RestController
@RequestMapping("/admin/meetings")
public class MeetingAdminController {
  private final ListAdminMeetingsUseCase listUseCase;

  @GetMapping
  public ResponseEnvelope<AdminMeetingListResponseDTO> list(
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    var view = listUseCase.execute(limit, cursor);
    return ResponseEnvelope.ok(view.toDto(), context());
  }
}
```

按现有 admin controller 模式写（authentication、tenant context、envelope）。

- [ ] **Step 4: 写测试**

unit test（use case mock repository）+ IT（Testcontainers，调真实端点）。

- [ ] **Step 5: 跑 mvn verify**

```bash
cd apps/meeting-api
./mvnw -pl meeting-api-app,meeting-api-adapter -am test 2>&1 | tail
```

Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add apps/meeting-api/
git commit -m "feat(meeting-api): add GET /admin/meetings endpoint"
```

### Task 6.3：ai-worker-web MeetingsPage 接入 admin BFF list

**Files:**
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts`（如果尚未有 listAdminMeetings）
- Modify: `apps/ai-worker-web/src/features/meetings/queries.ts`
- Modify: `apps/ai-worker-web/src/pages/MeetingsPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/__tests__/MeetingsPage.test.tsx`

- [ ] **Step 1: 加 listAdminMeetings 到 endpoints.ts**

```ts
// 在 endpoints.ts 中
export async function listAdminMeetings(opts?: { limit?: number; cursor?: string }) {
  const params = new URLSearchParams();
  if (opts?.limit) params.set("limit", String(opts.limit));
  if (opts?.cursor) params.set("cursor", opts.cursor);
  return apiFetch(`/admin/meetings?${params}`);
}
```

- [ ] **Step 2: 加 useAdminMeetingsQuery**

```ts
// apps/ai-worker-web/src/features/meetings/queries.ts
import { listAdminMeetings } from "@/shared/api/endpoints";

export function useAdminMeetingsQuery() {
  return useQuery({
    queryKey: ["admin", "meetings"],
    queryFn: () => listAdminMeetings(),
  });
}
```

- [ ] **Step 3: 写 MeetingsPage 测试**

```tsx
// apps/ai-worker-web/src/pages/__tests__/MeetingsPage.test.tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MeetingsPage } from "../MeetingsPage";
import * as endpoints from "@/shared/api/endpoints";

vi.spyOn(endpoints, "listAdminMeetings").mockResolvedValue({
  items: [
    { meetingId: "mtg_01", title: "测试会议", securityLevel: "INTERNAL", status: "READY", createdAt: "2026-05-26T10:00:00Z", language: "zh" },
  ],
  nextCursor: null,
});

describe("MeetingsPage landing", () => {
  it("renders meetings list and entry-point actions", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <MeetingsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("测试会议")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "新建会议工作流" })).toHaveAttribute("href", "/meetings/new");
    expect(screen.getByRole("link", { name: "声纹录入" })).toHaveAttribute("href", "/enrollment");
  });
});
```

- [ ] **Step 4: 重写 MeetingsPage**

```tsx
// apps/ai-worker-web/src/pages/MeetingsPage.tsx
import { Link } from "react-router-dom";
import { useAdminMeetingsQuery } from "@/features/meetings/queries";
import { formatDate } from "@/shared/utils/formatters";

export function MeetingsPage() {
  const { data, isPending, error } = useAdminMeetingsQuery();
  const items = data?.items ?? [];

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">运营工作站</h1>
          <p className="page-subtitle">选择会议进入工作站，或新建一个流程。</p>
        </div>
      </header>

      <section className="grid">
        <Link className="card stack" to="/meetings/new" style={{ textDecoration: "none", color: "inherit" }}>
          <strong>新建会议工作流</strong>
          <span className="page-subtitle">建会议 · 上传 · 术语 · 文档 · 启动 worker</span>
        </Link>
        <Link className="card stack" to="/enrollment" style={{ textDecoration: "none", color: "inherit" }}>
          <strong>声纹录入</strong>
          <span className="page-subtitle">为人员录入声纹样本，建立档案。</span>
        </Link>
      </section>

      <section className="card stack">
        <strong>近期会议</strong>
        {isPending ? <p className="page-subtitle" aria-busy="true">加载中…</p> : null}
        {error ? <div className="banner banner--danger" role="alert"><strong className="banner__title">加载失败</strong></div> : null}
        {!isPending && items.length === 0 ? <div className="empty-state"><strong>暂无会议</strong><span>点击「新建会议工作流」开始。</span></div> : null}
        {items.length > 0 ? (
          <table className="data-table">
            <thead><tr><th>标题</th><th>安全</th><th>状态</th><th>语言</th><th>创建时间</th></tr></thead>
            <tbody>
              {items.map((m) => (
                <tr key={m.meetingId}>
                  <td><Link to={`/meetings/${m.meetingId}`}>{m.title}</Link></td>
                  <td><span className="pill pill--info">{m.securityLevel}</span></td>
                  <td><span className="pill pill--info">{m.status}</span></td>
                  <td>{m.language}</td>
                  <td>{formatDate(m.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </div>
  );
}
```

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd apps/ai-worker-web
npm test -- src/pages/__tests__/MeetingsPage.test.tsx 2>&1 | tail
```

Expected: PASS

```bash
git add apps/ai-worker-web/src/
git commit -m "feat(ai-worker-web): wire MeetingsPage to admin BFF list endpoint"
```

### Task 6.4：EnrollmentPage 重写

**Files:**
- Modify: `apps/ai-worker-web/src/features/enrollment/queries.ts`
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.tsx`

- [ ] **Step 1: 写 enrollment queries**

```ts
// apps/ai-worker-web/src/features/enrollment/queries.ts
import { useMutation } from "@tanstack/react-query";
import { commitEnrollment, createEnrollmentSession, previewEnrollment, uploadEnrollmentAudio, searchPersons } from "@/shared/api/endpoints";

export function useCreateEnrollmentSession() { return useMutation({ mutationFn: (personId: string | null) => createEnrollmentSession(personId) }); }
export function useUploadEnrollmentAudio() { return useMutation({ mutationFn: ({ sessionId, file }: { sessionId: string; file: File }) => uploadEnrollmentAudio(sessionId, file) }); }
export function usePreviewEnrollment() { return useMutation({ mutationFn: previewEnrollment }); }
export function useCommitEnrollment() { return useMutation({ mutationFn: commitEnrollment }); }
```

- [ ] **Step 2: 重写 EnrollmentPage**

每段加 `<label htmlFor>`、`name`，文件 input 包 dropzone，质量分用环形 svg 阈值色（< 0.5 琥珀，≥ 0.5 海青）+ `Intl.NumberFormat`。

- [ ] **Step 3: 跑测试 + 提交**

```bash
npm test -- src/pages/__tests__/EnrollmentPage.test.tsx 2>&1 | tail
git add apps/ai-worker-web/src/
git commit -m "feat(ai-worker-web): rewrite EnrollmentPage with 3 controlled sections and quality ring"
```

### Task 6.5：Phase 6 全量回归

- [ ] **Step 1: 跑全部测试**

```bash
cd packages/meeting-contracts && npm run check
cd ../../apps/meeting-web && npm test && npm run build && npm run lint
cd ../ai-worker-web && npm test && npm run build
cd ../meeting-api && ./mvnw verify -q
```

Expected: 全绿。

- [ ] **Step 2: 浏览器手测桌面 + 移动**

启动两 app，至少覆盖：

- meeting-web：`/meetings` URL 过滤、`/meetings/:id` 概览、`/meetings/:id/transcript` 编辑 → STALE banner、`/meetings/:id/items` 4 色、`/rag` 双列、`/admin/break-glass`「应急访问」文案、移动断点抽屉
- ai-worker-web：`/meetings` admin BFF 列表、`/meetings/new` wizard 8 步推进、`/enrollment` 三段 + 质量环

- [ ] **Step 3: 提交所有剩余改动**

```bash
git status
git add -A
git commit -m "chore: phase 6 final touch-ups"
```

---

## 合并到 master

### Task M.1：rebase 与冲突检查

- [ ] **Step 1: 从最新 master rebase**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git fetch origin master
cd .worktrees/frontends-editorial-v2
git rebase origin/master
```

如有冲突，解决后 `git rebase --continue`。

- [ ] **Step 2: 跑全 CI 矩阵**

```bash
cd packages/meeting-contracts && npm run check
cd ../../apps/meeting-web && npm test && npm run build
cd ../ai-worker-web && npm test && npm run build
cd ../meeting-api && ./mvnw verify -q
```

Expected: 全绿。

### Task M.2：合并

- [ ] **Step 1: 切回 master 合并**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git checkout master
git merge --no-ff frontends-editorial-v2 -m "feat: Iceberg frontend refactor - Swedish editorial cool-toned UI with backend phase semantics"
```

- [ ] **Step 2: 清理 worktree**

```bash
git worktree remove .worktrees/frontends-editorial-v2
git branch -d frontends-editorial-v2
```

- [ ] **Step 3: 推送（可选 — 由用户确认）**

```bash
# 等用户确认后再 git push origin master
```

---

## Self-Review 备注

实施时三个补注：

1. **`code-mapper / `import` 路径别名**：spec 中用 `@shared/*`、`@features/*`、`@app/*`、`@/...` 等别名，需要 `tsconfig.json` 与 `vite.config.ts` 已配。如果新加 `@shared/queries/*`、`@shared/stores/*` 别名不存在，参照现有 `@shared/api/*` 加 path mapping。

2. **测试断言迁移**：在新页面写完后，跑既有测试可能遇到 NavLink 文案变化（如 "RAG" → "问答"）。允许更新断言，但保持业务行为断言不变。

3. **大文件拆分顺序**：建议 Phase 5 各 Task 独立合并提交，方便回滚。每个文件 < 200 行是软目标，超出时按职责再切分。
