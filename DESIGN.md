# Design System

## Overview

本地会议智能系统的视觉设计语言。面向技术团队的内部工具，强调**现代·简洁·智能**的品牌特质。

设计目标：
- 高信息密度 + 清晰的视觉层级
- 深色主题 + 明亮的技术蓝，营造精密仪器感
- 精确的排版和间距节奏
- 强对比度，确保可读性和操作效率

## Color Palette

### Core Colors (OKLCH)

```css
/* 深色背景 - 精密仪器面板的深色底 */
--bg: oklch(0.10 0.000 0);              /* 接近黑色，纯中性 */
--surface: oklch(0.14 0.000 0);         /* 卡片/面板背景 */
--surface-raised: oklch(0.17 0.000 0);  /* 悬浮元素 */

/* 文本 - 高对比度 */
--ink-1: oklch(0.95 0.000 0);           /* 主要文本 */
--ink-2: oklch(0.75 0.000 0);           /* 次要文本 */
--ink-3: oklch(0.55 0.000 0);           /* 辅助文本 */
--ink-4: oklch(0.35 0.000 0);           /* 占位文本 */

/* 分割线 */
--line-1: oklch(0.25 0.000 0);          /* 主要分割线 */
--line-2: oklch(0.20 0.000 0);          /* 次要分割线 */
--line-3: oklch(0.16 0.000 0);          /* 微弱分割线 */

/* 品牌主色 - 明亮的技术蓝（基于种子色 200° 调整） */
--primary: oklch(0.65 0.14 210);        /* 明亮、饱和的科技蓝 */
--primary-hover: oklch(0.70 0.15 210);  /* hover 更亮 */
--primary-active: oklch(0.60 0.13 210); /* active 稍暗 */
--primary-soft: oklch(0.25 0.08 210);   /* 柔和背景 */
--primary-ink: oklch(0.80 0.12 210);    /* 在深色背景上的文字色 */

/* 强调色 - 青绿色（与主色形成冷色系和谐） */
--accent: oklch(0.70 0.13 180);         /* 明亮的青色 */
--accent-soft: oklch(0.22 0.08 180);    /* 柔和背景 */
--accent-ink: oklch(0.75 0.12 180);     /* 文字色 */

/* 语义色 */
--success: oklch(0.68 0.15 145);        /* 翠绿色 */
--success-soft: oklch(0.22 0.08 145);
--success-ink: oklch(0.75 0.14 145);

--warn: oklch(0.70 0.15 70);            /* 琥珀色 */
--warn-soft: oklch(0.22 0.08 70);
--warn-ink: oklch(0.78 0.14 70);

--danger: oklch(0.60 0.18 20);          /* 朱红色 */
--danger-soft: oklch(0.20 0.08 20);
--danger-ink: oklch(0.70 0.16 20);
```

### 使用原则

- **背景为纯黑系**：品牌个性通过明亮的蓝色主色传达，背景保持中性
- **高对比度**：所有文本对比度 ≥ 7:1，确保长时间使用的可读性
- **饱和色用于强调**：主色、强调色、语义色都较饱和，用于按钮、状态标签、数据可视化
- **白色文字在饱和色填充上**：所有彩色按钮、badge 都用白色或浅色文字

## Typography

### Font Families

```css
--font-sans: "Inter", -apple-system, BlinkMacSystemFont, "SF Pro Text",
             "Segoe UI", "Helvetica Neue", "PingFang SC", "Microsoft YaHei",
             sans-serif;

--font-mono: "JetBrains Mono", "Fira Code", "SF Mono", "Consolas",
             "Liberation Mono", monospace;
```

- **Sans**: Inter（主要），优先使用系统字体作为后备
- **Mono**: 用于代码、ID、时间戳、进度百分比等需要精确对齐的内容

### Type Scale

```css
/* 标题 */
--text-3xl: 32px / 1.2 / -0.02em / 600;   /* 页面主标题 */
--text-2xl: 26px / 1.25 / -0.015em / 600; /* 区块标题 */
--text-xl: 22px / 1.3 / -0.01em / 600;    /* 子标题 */
--text-lg: 18px / 1.4 / -0.005em / 600;   /* 小标题 */

/* 正文 */
--text-base: 14px / 1.5 / 0 / 400;        /* 正文（主要） */
--text-sm: 13px / 1.45 / 0 / 400;         /* 小正文 */
--text-xs: 12px / 1.4 / 0 / 400;          /* 辅助文本 */
--text-2xs: 11px / 1.35 / 0.01em / 500;   /* 标签、提示 */

/* 特殊 */
--text-mono-base: 13px / 1.5 / 0 / 400;   /* 等宽正文 */
--text-mono-sm: 12px / 1.45 / 0 / 400;    /* 等宽小字 */
```

### 排版原则

- **更强的字重对比**：标题统一用 600，正文用 400，避免 500 的模糊中间态
- **负字距收紧大标题**：24px+ 的标题用 -0.01em 到 -0.02em
- **等宽字体用于数据**：ID、时间戳、百分比、时长用 mono 字体，确保视觉对齐
- **行高紧凑**：标题 1.2-1.3，正文 1.45-1.5，比常规设计稍紧凑以提高信息密度

## Spacing & Layout

### Spacing Scale

```css
--space-1: 4px;
--space-2: 8px;
--space-3: 12px;
--space-4: 16px;
--space-5: 20px;
--space-6: 24px;
--space-8: 32px;
--space-10: 40px;
--space-12: 48px;
--space-16: 64px;
```

### Radius

```css
--radius-s: 4px;   /* 小元素：badge、pill */
--radius-m: 6px;   /* 中等：button、input */
--radius-l: 8px;   /* 大元素：card、modal */
--radius-xl: 12px; /* 大型容器 */
```

**更小的圆角**：从原来的 6/10/14/20px 缩小到 4/6/8/12px，营造更精确、工具化的感觉。

### Layout Grid

- **侧边栏**：240px（桌面）→ 200px（平板）→ fixed drawer（移动）
- **主内容区最大宽度**：1440px（meeting-web 有侧边栏），1280px（ai-worker-web 无侧边栏）
- **内容区 padding**：24px（桌面）→ 16px（移动）

## Components

### Button

**四种变体：**
- **Primary** (--primary 填充 + 白色文字)：主要操作
- **Secondary** (--surface 背景 + --primary-ink 文字)：次要操作
- **Ghost** (透明背景 + --primary-ink 文字)：辅助操作
- **Danger** (--danger 填充 + 白色文字)：危险操作

**尺寸：**
- 默认：36px 高度，12px 横向 padding
- 小号：32px 高度，10px 横向 padding

**交互：**
- hover: 背景色变化 + 微妙的 scale(1.02)
- active: scale(0.98)
- focus-visible: 2px 外描边 + 4px 柔和阴影
- disabled: opacity 0.5 + cursor not-allowed

### Input & Form

- **高度**：36px（与 button 对齐）
- **边框**：1px solid --line-1，focus 时变为 --primary + 4px 柔和阴影
- **背景**：--surface（与页面背景区分）
- **placeholder**：--ink-4（确保对比度）

### Card

- **背景**：--surface
- **边框**：1px solid --line-2
- **圆角**：--radius-l (8px)
- **阴影**：微妙的 0 2px 8px rgba(0,0,0,0.3)
- **padding**：16px（默认）

### Badge / Pill

- **高度**：22px
- **padding**：0 8px
- **字体**：11px / 500 / uppercase (可选)
- **圆角**：--radius-s (4px)
- **变体**：info / success / warn / danger / neutral
- **背景**：语义色的 -soft 变体 + 对应的 -ink 文字色

### Table

- **背景**：--surface
- **边框**：1px solid --line-2（外边框）+ --line-3（行间分割线）
- **表头**：--ink-3 文字，12px / 600 / uppercase / 0.04em
- **行高**：44px（确保可点击性）
- **hover**：行背景变为 --surface-raised
- **对齐**：数字列右对齐 + tabular-nums

### Progress Bar

- **高度**：4px
- **背景**：--line-1
- **填充**：--primary（进行中）/ --success（完成）/ --danger（失败）
- **圆角**：2px
- **过渡**：width 200ms ease

### Status Dot

- **尺寸**：8px 圆形
- **颜色**：匹配 badge 的语义色系统
- **用途**：任务状态、连接状态、实时指示器

## Motion

### Transitions

```css
--transition-fast: 120ms ease;
--transition-base: 180ms ease;
--transition-slow: 240ms ease;
```

- **快速**：hover、focus 状态变化
- **基础**：展开/收起、淡入淡出
- **慢速**：页面切换、drawer 滑入

### Easing

- **ease-out-quad**: cubic-bezier(0.5, 1, 0.89, 1) - 元素进入
- **ease-out-quart**: cubic-bezier(0.25, 1, 0.5, 1) - drawer、modal
- **ease-in-out**: cubic-bezier(0.4, 0, 0.2, 1) - 位置变化

### 减少动效模式

所有动画都有 `@media (prefers-reduced-motion: reduce)` 替代方案：
- 进入/退出动画 → 简单淡入淡出或直接显示
- 滚动动画 → 禁用
- 循环动画（loading spinner）→ 保留但降低速度

## Shadows

```css
--shadow-1: 0 1px 3px rgba(0, 0, 0, 0.4);             /* 微妙层次 */
--shadow-2: 0 4px 12px rgba(0, 0, 0, 0.5);            /* 浮起元素 */
--shadow-3: 0 12px 32px rgba(0, 0, 0, 0.6);           /* modal、drawer */
--shadow-focus: 0 0 0 4px var(--primary-soft);         /* focus ring */
```

深色主题下，阴影更深、更明显，用于建立空间层次。

## Iconography

- **风格**：线性图标（stroke-width: 1.5-2px）
- **尺寸**：16px（inline）/ 20px（button）/ 24px（标题旁）
- **颜色**：继承父元素文字色
- **库**：推荐 Lucide 或 Phosphor（一致的线性风格）

## Data Visualization

### 图表配色

使用品牌色系统：
- 单色系列：--primary 的不同明度
- 多色系列：--primary, --accent, --success, --warn, --danger
- 背景：--surface
- 网格线：--line-2

### 实时数据展示

- **进度条**：明确的百分比 + 等宽字体
- **时间戳**：统一用 mono 字体 + tabular-nums
- **状态变化**：配合 SSE 实时更新，用颜色 + 图标 + 文字三重信号

## Accessibility

- ✅ **对比度**：所有文本对比度 ≥ 7:1（深色主题更容易达到）
- ✅ **焦点状态**：2px 外描边 + 4px 柔和阴影，明显可见
- ✅ **键盘导航**：所有交互元素可访问，Tab 顺序符合逻辑
- ✅ **减少动效**：全局 prefers-reduced-motion 支持
- ✅ **语义 HTML**：正确使用 `<button>`, `<nav>`, `<main>`, `<table>` 等
- ✅ **ARIA 标签**：动态内容、状态变化、表单错误关联

## Implementation Notes

### CSS 变量命名

- 所有 token 用 CSS custom properties
- 命名格式：`--category-variant`（如 `--ink-2`, `--primary-soft`）
- 避免 `--color-*` 前缀（冗余）

### 组件类名

- BEM 风格：`.block__element--modifier`
- 状态用 `data-*` 属性：`data-state="active"`, `data-loading="true"`
- 工具类最小化，优先用组件类

### 响应式策略

- **桌面优先**：内部工具主要在办公电脑上使用
- **断点**：1280px（平板）/ 768px（移动）
- **移动端**：侧边栏变为 drawer，表格可横向滚动，保持信息密度

### 浏览器支持

- **现代浏览器**：Chrome/Edge 最近 2 版本，Firefox 最近 2 版本，Safari 最近 2 版本
- **CSS 特性**：CSS Grid, Flexbox, CSS Custom Properties, `oklch()`（需 fallback）
- **OKLCH fallback**：在不支持的浏览器中用 `rgb()` fallback

## Design Tokens Export

完整的 CSS tokens 在 `src/shared/styles/tokens.css`（两个前端共享），使用时通过 `@import` 引入。
