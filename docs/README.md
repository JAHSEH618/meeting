# Docs

文档入口（仅列出仓库内实际跟踪的文档；`spec.md` / `SPEC.md` 系列为本地开发文件，已在 `.gitignore` 中忽略，不随仓库分发）：

- `本地会议智能系统技术方案文档-优化版.md`: 完整技术方案（术语、目标、架构、选型、Pipeline、安全、部署）
- `structure.md`: 完整逻辑视图（详细业务域 + 队列 + 模型层 mermaid）
- `app-api-contracts.md`: 应用间 API、消息、回调和 JSON 契约
- `model-registry.md`: AI 模型注册与版本策略
- `runbooks/`: 运维 / 验收 runbook（Apple Silicon、备份恢复、legal hold、阶段验收）
- `decisions/`: 架构决策记录（ADR）
- `ddls/`: PostgreSQL 数据表 DDL 评审快照（非运行时事实源；运行时 schema 以 meeting-api Flyway 迁移为准）

跨工程契约的唯一事实源在 [`../packages/meeting-contracts/`](../packages/meeting-contracts/)。
各工程工程说明见其各自的 `README.md`（`apps/*`、`packages/meeting-contracts`、`infra/meeting-infra`）。
部署见 [`../deploy/DEPLOY.md`](../deploy/DEPLOY.md)；本地启停脚本见 [`../scripts/README.md`](../scripts/README.md)。
