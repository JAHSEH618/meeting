# ai-worker-web

React 18 + Vite + TypeScript strict 的 Operator "Workstation" SPA。详见 [`CLAUDE.md`](CLAUDE.md)。

## 本地命令

### 开发

```bash
npm install           # 一次性安装依赖
npm run dev           # Vite dev server :5174, base /workstation/
npm run build         # 构建生产版本
```

### 测试与验证（每个阶段完成后必跑）

```bash
# 单元测试（Vitest）
npm test              # 运行所有测试
npm run lint          # ESLint

# 类型检查（TypeScript strict）
npm run type-check    # 即 tsc --noEmit

# E2E 测试（Playwright）
npm run e2e

# Codegen（从 contracts 重新生成 Public API 类型）
npm run codegen
```

**CI 门禁命令：**
```bash
npm run type-check && npm test && npm run build
```

## 架构

- **双后端模式：** Admin BFF (`/admin/*` → ai-worker) + Public API (`/api/*` → Java)
- **Auth：** Java mints JWT (aud=ai-worker-admin), BFF verifies & forwards
- **Base path：** Must build under `/workstation/` to match mount prefix
