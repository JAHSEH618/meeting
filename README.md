# Meeting Monorepo

本仓库按 5 个工程管理 MVP：

```text
apps/meeting-web        React SPA
apps/meeting-api        Java 17 + Spring Boot + COLA-V5
apps/ai-worker          Python 3.11+ AI worker
packages/meeting-contracts  OpenAPI / JSON Schema / error code contracts
infra/meeting-infra     docker-compose / k8s / terraform / deploy scripts
docs/                   product spec, architecture and technical design
```

一期仍按 3 类主要服务交付：`meeting-web`、`meeting-api`、`ai-worker`。`meeting-contracts` 和 `meeting-infra` 是独立工程边界，但不代表新增业务服务。

