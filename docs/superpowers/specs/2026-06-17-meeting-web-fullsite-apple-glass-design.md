# Meeting Web 全站 Apple 毛玻璃大胆化设计

> 日期：2026-06-17 · 状态：待用户复核
> 范围：`apps/meeting-web` 全路由视觉与交互密度重构；`apps/ai-worker-web` 仅保留已合入的同源 tokens/组件，不进入本次逐页套版范围。
> 设计基准：`docs/design-apple-preview.html`、`docs/DESIGN_FINAL.md`、`docs/design-apple-glass.md`。
> 取代：当前 `feature/extreme-glass-design` 合入后的半成品状态，以及视觉层面的 `2026-06-12-apple-style-frontends-design.md`。

## 1. 问题陈述

当前 `master` 已合入前端重构，但实际效果不稳定，核心原因不是 Vite 或 nginx，而是 CSS 层级和页面套版没有完成：

- `glass-design.css` 先导入，`app.css` 后导入；后者重新定义 `.page`、`.card`、`.button`、`.modal-panel`、`.data-table` 等同名选择器，覆盖了 Apple 毛玻璃系统。
- `tokens.css` 已切到浅色 Apple 风格，但很多组件仍使用旧控制台密度和旧变量假设，形成“浅色 tokens + 旧布局”的混合状态。
- `/login` 仍是普通居中卡片，和进入系统后的大胆视觉断裂。
- `/meetings` 有 Hero 和 stats，但没有完整承接 preview 的 12 列不规则布局、Hero glass card、入口页信息层次。
- 合规、审计、删除任务等密集页面若直接套 120px Hero，会牺牲扫描效率；但如果不套版，又无法满足“全站大胆化”。

本次目标是按用户选择的 **C. 分层大胆化** 落地：全站采用 Apple vibrancy、浅色径向背景、玻璃卡和大胆标题，但按页面类型控制信息密度。

## 2. 设计原则

1. **全站统一，但不是全站同尺寸**  
   登录、会议入口、详情流程、合规表格四类页面使用同一视觉语言，标题、留白、卡片尺寸按任务密度分层。

2. **参考 preview，不复制 marketing 页面结构**  
   `design-apple-preview.html` 的设计资产是浅色背景、固定光晕、黑色主按钮、玻璃卡、强 typography、12 列不规则网格和 900px modal。meeting-web 是业务控制台，保留左侧 rail，不改为顶部营销导航。

3. **先修 CSS 系统，再逐页套版**  
   必须先解决 `glass-design.css` 被 `app.css` 覆盖的问题，再定义页面 variant 和通用组件类，最后逐页改 JSX。否则会继续出现局部生效和局部回退。

4. **大胆但可操作**  
   `/meetings` 和 `/login` 可以接近 preview 的戏剧化视觉；合规页、审计页、导出页必须保留表格密度、批量扫描和表单效率。

5. **不引入新依赖**  
   不新增图标库、字体包、动画库或 CSS framework。使用现有 React、CSS、Vite。

## 3. 页面分层

### 3.1 `auth-page`：登录页

路由：`/login`

登录页是用户第一屏，必须成为全站视觉名片，不走左侧 rail。

布局：
- 全屏 `auth-page`，背景使用 preview 的浅色线性渐变 + 蓝色径向光晕。
- 宽屏为两列：左侧品牌 Hero，右侧登录玻璃卡。移动端上下堆叠。
- 左侧标题使用 `clamp(56px, 8vw, 104px)`，不超过 12ch，形成强换行。
- 登录卡宽度 `min(520px, 100%)`，`blur(60px-80px)`，圆角 32px，内边距 48-64px。

内容：
- 标题：`本地会议智能系统`
- 副标题：突出转录、纪要、RAG、合规留痕，不写冗长功能说明。
- 表单保留账号、密码、错误提示、submit disabled、`from` 跳转逻辑。
- 移除 inline style，使用 `auth-page`、`auth-hero`、`auth-card`、`auth-form` 类。

### 3.2 `page--hero`：入口页

路由：`/meetings`

这是系统首页，最接近 `design-apple-preview.html`。

布局：
- 左侧 rail 保留，主内容使用 `page page--hero`。
- Hero 标题最大 120px，正文 22-24px，动作按钮使用黑色主按钮 + glass 次按钮。
- Hero 下方使用 `stats-grid`，数字 72-80px。
- 再下方使用 `grid-12`：会议列表/近期活动/处理状态/知识库入口等按 6/6、4/4/4、8/4 组合。
- 列表表格放在 `glass-panel glass-panel--table` 中，不再裸卡片。

必须修正：
- 统计“已完成”基于 `MeetingStatus === "SUCCEEDED"`，不使用不存在的 `READY`。
- 保留搜索和空态，但视觉改成 preview 体系。

### 3.3 `page--workbench`：业务工作页

路由族：
- `/meetings/new`
- `/meetings/:meetingId`
- `/meetings/:meetingId/audio`
- `/meetings/:meetingId/tasks/:taskId`
- `/meetings/:meetingId/transcript`
- `/meetings/:meetingId/minutes`
- `/meetings/:meetingId/items`
- `/meetings/:meetingId/speakers`
- `/rag`
- `/documents`
- `/speaker-profiles`

布局：
- 标题使用 `clamp(48px, 6vw, 88px)`，保持大胆但不抢占全部首屏。
- 顶部使用 `page-hero page-hero--workbench`，包含标题、副标题、关键操作。
- 主体使用 `grid-12` 或 `glass-panel`，表单、上传、转录、RAG 答案等保持清晰分区。
- 详情页可用 8/4 布局：主内容 8 列，侧栏状态/操作 4 列。
- 转录、审阅、任务步骤等长内容不加过度 hover 位移，避免滚动时视觉噪音。

### 3.4 `page--dense`：合规和表格页

路由族：
- `/admin/legal-holds`
- `/admin/deletion-jobs`
- `/admin/break-glass`
- `/admin/audit-events`
- `/meetings/:meetingId/exports`

布局：
- 标题使用 `clamp(40px, 5vw, 64px)`。
- 顶部仍有玻璃 Hero，但高度收敛，首屏要露出列表/表格。
- 卡片使用 `glass-panel--compact`，内边距 24-32px。
- 表格行高保持 44-52px，不使用 80px 卡片行。
- 操作按钮仍可大胆，但避免所有次要操作都变成黑色主按钮。

这类页面的目标是“有 Apple 视觉，但仍然像专业工具”。

## 4. CSS 架构

### 4.1 导入与覆盖顺序

保留：

```css
@import "../shared/styles/tokens.css";
@import "../shared/styles/glass-design.css";
```

但需要重构 `app.css`：

- `tokens.css`：只放 token、body、基础 typography、focus、reduced-motion。
- `glass-design.css`：放全局视觉原语和通用组件：background、glass-panel、button、modal、grid-12、stats、table、auth、page variants。
- `app.css`：只放 meeting-web 外壳、路由布局、少量业务组件补充。不得再次定义会覆盖玻璃系统的 `.card`、`.button`、`.modal-panel`、`.data-table` 主样式；如确需覆盖，必须使用 variant class。

### 4.2 核心类名

页面：
- `page`
- `page--hero`
- `page--workbench`
- `page--dense`
- `page-hero`
- `page-hero--compact`
- `page-hero__label`
- `page-hero__title`
- `page-hero__subtitle`
- `page-hero__actions`

玻璃容器：
- `glass-panel`
- `glass-panel--hero`
- `glass-panel--compact`
- `glass-panel--table`
- `glass-panel--flat`

布局：
- `grid-12`
- `span-4`
- `span-6`
- `span-8`
- `span-12`
- `asymmetric-grid`

数据展示：
- `stats-grid`
- `stat-card`
- `stat-card__value`
- `stat-card__label`

认证：
- `auth-page`
- `auth-hero`
- `auth-hero__label`
- `auth-hero__title`
- `auth-hero__subtitle`
- `auth-card`
- `auth-form`

### 4.3 Token 约束

保留当前浅色主轴，但补齐向后兼容变量，避免旧页面出现未定义变量：

- `--primary-active`
- `--primary-ink`
- `--accent-active`
- `--accent-soft`
- `--accent-ink`
- `--success-ink`
- `--warn-ink`
- `--danger-ink`
- `--line-3`
- `--surface-raised`
- `--surface-sunken`
- `--ink-4`
- `--focus`
- `--radius-pill`

这些变量可以映射到 Apple 体系，不应回到深色 oklch 体系。

## 5. 组件与页面改造

### 5.1 登录页

修改 `apps/meeting-web/src/features/auth/LoginPage.tsx`：
- 替换 `<main className="page">` 为 `<main className="auth-page">`。
- 新增品牌 Hero 文案。
- 表单卡片使用 `auth-card glass-panel`。
- 主按钮使用 `button button--primary`，不能只写 `primary`。
- 保留测试可通过的 label、button 文本和行为。

### 5.2 Shell

修改 `apps/meeting-web/src/app/App.tsx` 和 `app.css`：
- rail 使用 glass header 质感，宽度 260-280px。
- brand 改为更短更强的 `Meeting` 或保留 `会议系统`，但字重/留白按 preview 调整。
- `+ 新建会议` 使用黑色主按钮，与 preview 主按钮一致。
- active nav 使用蓝色 soft 背景，不用旧左边 3px 线。
- main content 最大宽度从 1600px 扩到 1800px，但 dense 页面仍通过 page variant 控制行宽。

### 5.3 会议入口

修改 `MeetingListPage.tsx`：
- 使用 `page--hero`。
- Hero 标题可为 `会议智能平台` 或 `会议智能重新定义`。
- stats 使用 `stat-card`。
- 增加一块 `glass-panel glass-panel--hero` 概述卡，承接 preview 的 Hero Glass。
- 列表区域使用 `glass-panel--table`。

### 5.4 Dense 页面

优先改：
- `LegalHoldsPage`
- `DeletionJobsPage`
- `AuditEventsPage`
- `BreakGlassPage`
- `ExportsPage`

改造原则：
- 顶部 `<div className="page-header">` 替换为 `page-hero page-hero--compact`。
- 主列表/表单 section 使用 `glass-panel glass-panel--compact`。
- 表格保留可读密度。
- modals 使用统一 `modal-panel`。

### 5.5 Workbench 页面

优先改：
- `MeetingDetailPage`
- `TaskProgressPage`
- `TranscriptPage`
- `RagPage`
- `DocumentsPage`

改造原则：
- 每页有明显标题区，但不做 120px。
- 关键信息使用 8/4 或 6/6 grid。
- 长文本和表单不要强制 hover 抬升。

## 6. 响应式与可访问性

- 1440px 以上：rail + 1800px 内容容器，`page--hero` 有最大视觉冲击。
- 1024-1439px：rail 收窄，grid 从 12 列降到 8 列。
- 768px 以下：rail 可离屏；页面标题降到 44-64px；grid 单列；登录页上下堆叠。
- 所有按钮保留 `:focus-visible`。
- modal 支持 ESC、背景点击关闭、`aria-modal`、`aria-labelledby`。
- `prefers-reduced-motion` 禁用 hover 抬升和 modal 动画。
- 中文标题不使用过度 letter spacing；大标题可以轻微负字距，但不让字重/字距导致重叠。

## 7. 验证门禁

必须运行：

```bash
cd apps/meeting-web
npm run build
npm test
```

建议补充：

```bash
cd apps/ai-worker-web
npm run build
npm test
```

视觉验证：
- 用浏览器检查 `/login`、`/meetings`、`/admin/legal-holds`、`/admin/audit-events`、一个会议详情页。
- 至少检查桌面宽屏和移动宽度。
- 确认 `glass-design.css` 没有再被 `app.css` 同名主选择器覆盖。

## 8. 不做

- 不把 meeting-web 改为纯顶部导航。
- 不新增三方 UI kit 或动画库。
- 不引入字体文件。
- 不重写数据请求、auth、query 或路由结构。
- 不在所有页面强制 120px 标题。
- 不把表格行改成大卡片流。

## 9. 实施顺序

1. 修 tokens 向后兼容变量和 CSS 覆盖关系。
2. 重做登录页。
3. 重做 shell 和 `/meetings`。
4. 改 dense 合规页。
5. 改 workbench 页面。
6. 跑构建/测试和浏览器视觉检查。

这个顺序保证第一步就解决当前重构失效的根因，并让登录页和首页尽早成为可验证的视觉基准。
