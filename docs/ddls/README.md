# DDL

本目录保存数据库 DDL 源稿，来源于 `docs/spec.md`、`docs/app-api-contracts.md` 和完整技术方案中的数据库设计章节。

- `001_initial_schema.sql`: PostgreSQL 15+ / pgvector 初始 schema，包含核心表、枚举、索引、RLS policy 和更新时间触发器。

后续接入 Flyway 或 Liquibase 时，可以按业务域拆分为正式 migration；这里先作为跨团队审阅和实现对齐的单一 DDL 文档。
