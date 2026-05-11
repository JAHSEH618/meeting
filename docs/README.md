# Docs

文档入口：

- `spec.md`: 一期可执行规格
- `../apps/meeting-web/SPEC.md`: 前端工程规格
- `../apps/meeting-api/SPEC.md`: Java 后端工程规格
- `../apps/ai-worker/SPEC.md`: Python AI Worker 工程规格
- `../packages/meeting-contracts/SPEC.md`: 跨工程契约规格
- `../infra/meeting-infra/SPEC.md`: 基础设施工程规格
- `structure.md`: 架构图
- `app-api-contracts.md`: 应用间 API、消息、回调和 JSON 契约
- `ddls/`: PostgreSQL 数据表 DDL 源稿
- `本地会议智能系统技术方案文档-优化版.md`: 完整技术方案

`meeting-api` 内部 COLA-V5 子项目规格：

- `../apps/meeting-api/meeting-api-start/SPEC.md`: 启动、配置、profile、健康检查
- `../apps/meeting-api/meeting-api-client/SPEC.md`: DTO、Command、Query、Result、Facade、枚举、错误码
- `../apps/meeting-api/meeting-api-adapter/SPEC.md`: REST、SSE、internal callback、BFF 适配
- `../apps/meeting-api/meeting-api-app/SPEC.md`: 用例编排、事务、权限、outbox、幂等
- `../apps/meeting-api/meeting-api-domain/SPEC.md`: 聚合、实体、值对象、领域服务、领域事件和端口
- `../apps/meeting-api/meeting-api-infrastructure/SPEC.md`: PostgreSQL、TOS、RabbitMQ、DashScope、KMS、导出实现
