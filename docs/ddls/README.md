# DDL

本目录保存数据库 DDL 设计评审稿，来源于 `docs/spec.md`、`docs/app-api-contracts.md` 和完整技术方案中的数据库设计章节。

运行时数据库 schema 的事实源已经切换为 Flyway migration：

```text
apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/*.sql
```

- `001_initial_schema.sql`: PostgreSQL 15+ / pgvector 初始 schema 评审快照，包含核心表、枚举、索引、RLS policy 和更新时间触发器。

新增表、字段、索引、RLS policy 或 enum 时必须先修改 Flyway migration，再按需同步本目录评审稿。不得把本目录当作第二份运行时 DDL 事实源。
