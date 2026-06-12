# Apple 风格（Cupertino）双前端重构设计

> 日期：2026-06-12 · 状态：已采纳（用户目标直接指定：用 web-design-guidelines 重构 java/python 的 web 前端，苹果风格）
> 范围：`apps/meeting-web`（业务控制台，左侧 rail + 18 路由）与 `apps/ai-worker-web`(运营工作站，顶栏 + 8 路由)
> 取代视觉层面的 `2026-05-27-meeting-frontends-iceberg-refactor-design.md`（Iceberg 冷调瑞典编辑风）；其信息架构、类名体系、数据层（TanStack Query/Zustand）全部保留。

## 1. 方案选择

| 方案 | 描述 | 取舍 |
|---|---|---|
| A. 仅换 token 值 | 改 tokens.css 调色/圆角/字体 | 风险最低，但导航/按钮/卡片不动，"苹果感"出不来 |
| B. 重建组件库 | 新建 Button/Card 组件并改写全部页面 | 大改 DOM，破坏 173+92 个现有测试的选择器假设，超时间预算 |
| **C. 重主题 + 外壳精修（采纳）** | 保留类名架构与 DOM，token 值换成 Apple 体系并扩充（阴影/毛玻璃/胶囊），重写外壳（rail/顶栏）与核心组件（按钮/卡片/表格/表单/pill/modal）的 CSS | 测试按 role/text 选择 → 全绿可保；两 app 物理隔离但视觉同根的原则不变 |

理由：现有 Iceberg 架构（共享 token 名、BEM 类、纯 CSS、每 app 物理隔离）本身是好的；苹果风格是一次**视觉语言替换**，不是信息架构重构。

## 2. 视觉语言（Cupertino Light）

**两个 app 的 `src/shared/styles/tokens.css` 使用完全一致的内容**（物理隔离、字面同步）。

### 2.1 Token 值替换（同名换值）

```css
:root {
  --surface-base: #f5f5f7;            /* Apple 页面灰 */
  --surface-raised: #ffffff;
  --surface-sunken: #f2f2f7;          /* iOS systemGray6，输入/禁用区 */

  --ink-1: #1d1d1f;                   /* 主文 */
  --ink-2: #424245;                   /* 次文 */
  --ink-3: #6e6e73;                   /* 提示 */
  --ink-4: #aeaeb2;                   /* 微弱 */

  --line-1: rgba(0, 0, 0, 0.12);      /* 发丝主分隔 */
  --line-2: rgba(0, 0, 0, 0.07);
  --line-3: rgba(0, 0, 0, 0.045);

  --accent: #0071e3;                  /* Apple 蓝 */
  --accent-hover: #0064d0;   /* 深于 rest：满足 WIG hover 对比度高于静置 */
  --accent-active: #005bbd;
  --accent-soft: #e8f1fd;
  --accent-ink: #0058b0;

  --success: #1f8f3a;  --success-soft: #e9f8ee;  --success-ink: #166f2c;
  --warn:    #d97c00;  --warn-soft:    #fff4e5;  --warn-ink:    #9a5700;
  --danger:  #d70015;  --danger-soft:  #ffebe9;  --danger-ink:  #b3000c;

  --focus: #0071e3;
  /* ai-worker-web 独有的 --focus-ring/--focus-ring-offset/--border 同步换值：
     --focus-ring: #0071e3; --focus-ring-offset: #ffffff; --border: rgba(0,0,0,0.12); */

  --radius-s: 6px;  --radius-m: 10px;  --radius-l: 14px;
  --radius-xl: 20px;        /* 新增：modal/大卡片 */
  --radius-pill: 980px;     /* 新增：主按钮胶囊 */

  /* --space-* 八档不变 */

  --shadow-1: 0 1px 2px rgba(0, 0, 0, 0.04), 0 1px 1px rgba(0, 0, 0, 0.02);   /* 新增：静置卡片 */
  --shadow-2: 0 2px 8px rgba(0, 0, 0, 0.06), 0 8px 24px rgba(0, 0, 0, 0.06);  /* 新增：悬浮/下拉 */
  --shadow-3: 0 12px 32px rgba(0, 0, 0, 0.14), 0 2px 8px rgba(0, 0, 0, 0.06); /* 新增：modal */

  --chrome-bg: rgba(255, 255, 255, 0.72);            /* 新增：毛玻璃底 */
  --chrome-blur: saturate(180%) blur(20px);          /* 新增：backdrop-filter 值 */

  --font-sans: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Helvetica Neue",
               "PingFang SC", "Microsoft YaHei", sans-serif;   /* 去 Inter 优先 */
}
```

基础层补充（两 app 一致）：`body` 字号 14px 不变；新增 `h1,h2 { letter-spacing: -0.015em; text-wrap: balance; }`；保留既有 `:focus-visible`（2px outline + offset）、`prefers-reduced-motion`、`touch-action: manipulation`、`color-scheme: light`、skip-link。

### 2.2 组件规约（写进各 app 的 app.css / styles.css）

- **过渡**：统一 `transition: background-color .18s ease, border-color .18s ease, color .18s ease, box-shadow .18s ease, transform .18s ease;` —— 禁止 `transition: all`（WIG）；动画只用 transform/opacity。
- **按钮**：主按钮 = Apple 蓝胶囊（`border-radius: var(--radius-pill)`、白字、hover 加亮、active 轻微 `transform: scale(0.98)`）；次按钮 = `--surface-raised` 底 + 发丝边 + 圆角 `--radius-m`；危险按钮同形换 `--danger`；幽灵/链接按钮纯文字蓝。所有按钮 hover 态对比度高于静置态（WIG）。
- **卡片**：`--radius-l`、发丝边 `--line-2`、`--shadow-1`；可点卡片 hover 升 `--shadow-2`。
- **表格**：去重底色，行用 `--line-3` 发丝分隔；表头 12px、`--ink-3`、轻大写感（不强制 uppercase，中文为主）；数字列 `font-variant-numeric: tabular-nums`（WIG）；行 hover `--surface-sunken` 轻染。
- **表单**：输入框 `--surface-raised` 底 + 发丝边 + `--radius-m`，focus 时边框变 `--accent` 且 `box-shadow: 0 0 0 4px var(--accent-soft)`（保留 :focus-visible outline 的全局兜底）；label 13px `--ink-2`；错误文案行内、`--danger-ink`。
- **pill/badge**：保持 soft 底 + ink 字的映射（值已换 Apple 色）；圆角胶囊化。
- **banner**：左线改为整体 soft 底 + 发丝边 + `--radius-m`（保留四色变体类名）。
- **modal/dialog**：`--radius-xl`、`--shadow-3`、遮罩 `rgba(0,0,0,0.4)`；面板内 `overscroll-behavior: contain`（WIG）。
- **外壳**：
  - meeting-web `shell__rail`：浅灰 `--surface-base` 之上的毛玻璃白轨（`background: var(--chrome-bg); backdrop-filter: var(--chrome-blur);` + 右侧发丝边）；`shell__rail-link` 圆角 `--radius-m`，active = `--accent-soft` 底 + `--accent-ink` 字（iOS 侧栏选中态）；brand 17px/600/-0.02em。
  - ai-worker-web `layout__header`：`position: sticky; top: 0` 毛玻璃顶栏 + 底部发丝边；NavLink active = `--accent-soft` 胶囊；为 `backdrop-filter` 提供 `@supports` 兜底（不支持时退纯白）。
- **空态/加载**：文案以 `…` 结尾（现状已符合）；空态居中 `--ink-3`。

### 2.3 不做（YAGNI）

深色模式（现状 color-scheme: light，两 app 均无暗色需求）；新组件库；图标库引入；字体下载（系统字体栈零网络成本，天然 SF/苹方）；DOM 结构调整；React 组件 API 变更。

## 3. 约束与门禁

1. **测试全绿**：两 app 现有 vitest（173 + 92）按 role/text 选择，CSS-only 改动不应破坏；改动后跑 `npm test`、`tsc --noEmit`/`type-check`、`lint`、`build` 四门禁。
2. **预算**：纯 CSS 改动，meeting-web 首屏 gzip < 200KB 预算不受影响（无新依赖、无字体文件）。
3. **WIG 合规**：实现后用 web-design-guidelines 清单审一遍两 app 的外壳与核心 CSS/页面文件，按 `file:line` 修复。
4. **物理隔离**：禁止建跨 app 共享包；tokens.css 两份内容字面相同。
5. ai-worker-web 的 `base /workstation/`、内存 token、BFF DTO 手写等架构约束不动。

## 4. 实施切分

- 任务 A（meeting-web）：tokens.css 换值扩充 → app.css 外壳/组件规约落地 → 四门禁。
- 任务 B（ai-worker-web）：tokens.css 同步 → styles.css 外壳/组件规约落地 → 四门禁。
- 任务 C（审计收口）：web-design-guidelines 扫两 app 改动面 + 抽查页面文件，修复 findings；diff 两份 tokens.css 必须 0 差异。

A/B 无共享文件，可并行；C 串行收口。
