# Apple 风格双前端重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 meeting-web 与 ai-worker-web 从 Iceberg 冷调重主题为 Apple/Cupertino 风格（纯 CSS，DOM/类名/测试不动）。

**Architecture:** 同名 token 换值 + 新增阴影/毛玻璃/胶囊 token（两 app 的 tokens.css 字面相同），再在各 app 的主 CSS（app.css / styles.css）内按 §2.2 组件规约重写外壳与核心组件的视觉值。零新依赖、零 DOM 改动。

**Tech Stack:** 纯 CSS custom properties · Vite · Vitest/RTL 既有门禁。设计依据：`docs/superpowers/specs/2026-06-12-apple-style-frontends-design.md`（含完整 token 表与组件规约——实施前先读）。

---

### Task A: meeting-web 重主题

**Files:**
- Modify: `apps/meeting-web/src/shared/styles/tokens.css`（全文件替换 :root token 块）
- Modify: `apps/meeting-web/src/app/app.css`（按选择器组换视觉值；不改类名、不删选择器）

- [ ] **Step A1: 替换 tokens.css 的 :root 块**

用设计文档 §2.1 的完整 token 表替换现有 `:root`（surface/ink/line/accent/success/warn/danger/focus 换值；radius 改 6/10/14 并新增 `--radius-xl: 20px`、`--radius-pill: 980px`；新增 `--shadow-1/2/3`、`--chrome-bg`、`--chrome-blur`；`--font-sans` 改为 `-apple-system, BlinkMacSystemFont, "SF Pro Text", "Helvetica Neue", "PingFang SC", "Microsoft YaHei", sans-serif`；space 八档保留）。文件其余部分（body、focus-visible、skip-link、reduced-motion）保留，并在 body 规则后追加：

```css
h1, h2 { letter-spacing: -0.015em; text-wrap: balance; }
```

- [ ] **Step A2: app.css 外壳（shell__rail）**

```css
.shell__rail {
  background: var(--chrome-bg);
  backdrop-filter: var(--chrome-blur);
  -webkit-backdrop-filter: var(--chrome-blur);
  border-right: 1px solid var(--line-2);
}
@supports not (backdrop-filter: blur(1px)) {
  .shell__rail { background: var(--surface-raised); }
}
.shell__brand { font-size: 17px; font-weight: 600; letter-spacing: -0.02em; }
.shell__rail-link { border-radius: var(--radius-m); transition: background-color .18s ease, color .18s ease; }
.shell__rail-link:hover { background: var(--surface-sunken); color: var(--ink-1); }
.shell__rail-link.active { background: var(--accent-soft); color: var(--accent-ink); font-weight: 600; }
```

（在现有选择器上改值合并，保留布局属性 padding/width/gap 等。）

- [ ] **Step A3: app.css 组件组（按设计 §2.2 逐组落地）**

按钮（找到现有 .btn / button 类组）：主按钮 `border-radius: var(--radius-pill); background: var(--accent); color: #fff;` hover `--accent-hover`、active `--accent-active` + `transform: scale(0.98)`；次按钮白底发丝边 `--radius-m`；危险同形 `--danger`。卡片：`border-radius: var(--radius-l); border: 1px solid var(--line-2); box-shadow: var(--shadow-1);`。表格：表头 `font-size: 12px; color: var(--ink-3);`，行分隔 `border-bottom: 1px solid var(--line-3)`，数字列容器加 `font-variant-numeric: tabular-nums`（在 table 或 td 通用规则上加即可），行 hover `background: var(--surface-sunken)`。表单 .field 组：输入框 `border-radius: var(--radius-m)`，focus-visible 时 `border-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); outline: none;`（仅输入控件局部替换 outline，其余元素保留全局 focus-visible）。pill/badge：`border-radius: var(--radius-pill)`。banner：去左粗线改 `border: 1px solid var(--line-2); border-radius: var(--radius-m);`（四色变体保留 soft 底）。modal/dialog/抽屉类（如有）：`border-radius: var(--radius-xl); box-shadow: var(--shadow-3); overscroll-behavior: contain;` 遮罩 `rgba(0,0,0,0.4)`。全局：所有出现 `transition: all` 的地方改为枚举属性。

- [ ] **Step A4: 四门禁**

```bash
cd apps/meeting-web && npx tsc --noEmit && npm test && npm run lint && npm run build
```
Expected: 0 type errors · 173 tests pass · lint clean · build success。

### Task B: ai-worker-web 重主题

**Files:**
- Modify: `apps/ai-worker-web/src/shared/styles/tokens.css`（与 Task A Step A1 完全相同的 token 块；额外保留并换值该文件独有的 `--focus-ring: #0071e3; --focus-ring-offset: #ffffff; --border: rgba(0,0,0,0.12);`；同样追加 h1,h2 规则）
- Modify: `apps/ai-worker-web/src/styles.css`

- [ ] **Step B1: tokens.css 同步替换**（内容与 Task A Step A1 相同——除上述 3 个独有 token 外两文件字面一致；完成后 `diff` 两 app 的 tokens.css，差异必须仅为这 3 行）

- [ ] **Step B2: styles.css 外壳（layout__header 顶栏）**

```css
.layout__header {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--chrome-bg);
  backdrop-filter: var(--chrome-blur);
  -webkit-backdrop-filter: var(--chrome-blur);
  border-bottom: 1px solid var(--line-2);
}
@supports not (backdrop-filter: blur(1px)) {
  .layout__header { background: var(--surface-raised); }
}
.layout__brand { font-size: 17px; font-weight: 600; letter-spacing: -0.02em; }
.layout__nav a { border-radius: var(--radius-pill); padding: 6px 14px; transition: background-color .18s ease, color .18s ease; }
.layout__nav a:hover { background: var(--surface-sunken); color: var(--ink-1); }
.layout__nav a.active { background: var(--accent-soft); color: var(--accent-ink); font-weight: 600; }
```

（合并进现有选择器，保留既有布局属性。）

- [ ] **Step B3: styles.css 组件组** — 与 Task A Step A3 相同的规约，作用于本 app 现有类名（按钮/卡片/表格/field/pill/banner/modal/`transition: all` 清理）。规约全文见设计文档 §2.2；关键值：主按钮胶囊 Apple 蓝、卡片 `--radius-l`+`--shadow-1`+发丝边、表格发丝分隔+`tabular-nums`、输入 focus `--accent` 边框 + 4px `--accent-soft` 光环、modal `--radius-xl`+`--shadow-3`+`overscroll-behavior: contain`。

- [ ] **Step B4: 四门禁**

```bash
cd apps/ai-worker-web && npm run type-check && npm test && npm run lint && npm run build
```
Expected: 0 type errors · 92 tests pass · lint clean · build success。

### Task C: WIG 审计收口（A/B 完成后串行）

- [ ] **Step C1:** `diff apps/meeting-web/src/shared/styles/tokens.css apps/ai-worker-web/src/shared/styles/tokens.css` → 差异仅 3 个独有 token 行。
- [ ] **Step C2:** 用 web-design-guidelines 清单审两 app 的 tokens.css、app.css、styles.css、App 外壳 TSX 与 2-3 个代表页面（MeetingListPage / MeetingDetailPage / NewMeetingPage），输出 `file:line` findings 并修复（重点：transition: all 残留、outline-none 无替代、hover 态对比度、tabular-nums、overscroll-behavior、reduced-motion 仍生效）。
- [ ] **Step C3:** 复跑两 app 四门禁（同 A4/B4 命令）。

## Self-Review

- 覆盖：设计 §2.1（A1/B1）、§2.2 全组件（A2-A3/B2-B3）、§3 门禁（A4/B4/C3）、§4 切分（A∥B→C）✅
- 无占位符；类型一致性不适用（纯 CSS）；A/B 文件零交集可并行 ✅
- 提交策略：按工作区分批提交在总收口（任务 #9）统一做，与对齐修复的提交一起编排。
