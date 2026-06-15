# meeting-web

React 18 + Vite + TypeScript strict 的端用户 SPA。详见 [`SPEC.md`](SPEC.md) 与 [`CLAUDE.md`](CLAUDE.md)。

## 本地命令

### 开发

```bash
npm install           # 一次性安装依赖
npm run dev           # Vite dev server :5173，代理 /api -> :8080
npm run build         # 构建生产版本
```

### 测试与验证（每个阶段完成后必跑）

```bash
# 单元测试（Vitest）
npm test              # 运行所有测试
npm run test:watch    # watch 模式
npx vitest run src/path/to/test  # 单个测试

# 类型检查（TypeScript strict）
npx tsc --noEmit

# Lint
npm run lint

# E2E 测试（Playwright）
npm run e2e:install   # 一次性安装 chromium
npm run e2e

# Codegen（从 contracts 重新生成类型）
npm run codegen
```

**CI 门禁命令：**
```bash
npx tsc --noEmit && npm test
```

## 目录结构

```text
src/
  app/
  pages/
  features/
  shared/
  services/
  styles/
```
