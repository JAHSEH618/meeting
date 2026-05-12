# meeting-api-start Spec

## 1. 项目定位

`meeting-api-start` 是 Spring Boot 启动模块，负责应用启动、配置装配、profile、健康检查、组件扫描和运行时入口。它不承载业务规则。

## 1.1 开发准入

启动模块是架构边界和运行时配置的验收入口：

1. MVP-0 必须能启动 `local` profile，并装配 adapter、app、domain、infrastructure。
2. ArchUnit 测试固定放在 `src/test/java/com/meeting/api/ArchitectureBoundaryTest.java`，用于守住 COLA 依赖方向和禁止规则。
3. 启动模块不新增业务 service、Repository 或 Controller；需要业务逻辑时回到对应 COLA 模块。

## 2. 启动职责

1. 启动 Spring Boot 应用。
2. 装配 adapter、app、domain、infrastructure 模块。
3. 加载配置文件和环境变量。
4. 初始化健康检查。
5. 暴露应用端口，默认 `8080`。
6. 配置日志、trace、metrics。

## 3. 配置项

默认业务配置以 `docs/spec.md` §13 为事实来源，启动模块只维护 Spring Boot 运行时、profile、management endpoint 和依赖装配所需配置。避免在启动模块 SPEC 中复制完整业务默认值。

`application.yml` 至少包含启动项：

```yaml
server:
  port: 8080

spring:
  profiles:
    active: local

management:
  endpoints:
    web:
      exposure:
        include:
          - health
          - metrics
          - prometheus
```

生产 profile 必须显式配置或从环境变量注入：

| 配置键 | 默认 | 说明 |
|---|---|---|
| `server.tomcat.threads.max` | 200 | 普通 HTTP 请求线程，不承载 SSE 长连接执行 |
| `spring.datasource.hikari.maximum-pool-size` | 20 | 与 PostgreSQL 资源规格联动 |
| `spring.flyway.enabled` | true | prod 禁止跳过 migration |
| `meeting.rls.required` | true | prod 禁止关闭 |
| `meeting.sse.max-connections` | 500 | 单实例 SSE 上限 |
| `meeting.outbox.batch-size` | 100 | outbox 单批发布数量 |
| `meeting.outbox.poll-interval-ms` | 500 | outbox 轮询间隔 |
| `meeting.callback.timestamp-skew-seconds` | 300 | HMAC timestamp 容忍窗口 |
| `meeting.callback-events.retention-days` | 30 | callback 幂等重放保留 |
| `meeting.chunk.strategy-version` | 无默认值 | 首次 `MEETING_FULL_PIPELINE` 任务写入 `expectedInputVersion.chunkStrategyVersion` 的来源 |

敏感值通过环境变量注入：

1. 数据库密码。
2. RabbitMQ 密码。
3. TOS access key 和 secret key。
4. DashScope API key。
5. callback HMAC secret。
6. KMS 配置。
7. JWT / session secret。

## 4. Profile

建议 profile：

1. `local`：本地开发，允许使用 in-memory 占位实现或本地 compose。
2. `dev`：开发环境，连接共享基础设施。
3. `staging`：预生产，接近生产配置。
4. `prod`：生产，禁止 debug 和不安全配置。
5. `test`：自动化测试，使用测试库或容器。

profile 规则：

1. `prod` 不允许使用 in-memory repository。
2. `prod` 不允许缺少 callback HMAC secret。
3. `prod` 不允许关闭 RLS。
4. `prod` 不允许临时下载模型权重或写入测试 bucket。

## 5. 健康检查

健康检查至少包含：

1. 应用 liveness。
2. PostgreSQL connectivity。
3. PostgreSQL RLS tenant context smoke test。
4. RabbitMQ connectivity。
5. TOS connectivity。
6. DashScope 配置存在性，不在普通 health 中主动发送敏感内容。
7. outbox publisher 状态。
8. KMS connectivity 或 KMS 配置存在性。
9. RabbitMQ 必要队列存在性和 queue depth 摘要。

## 6. 启动失败条件

以下情况应 fail fast：

1. 必要环境变量缺失。
2. RLS 要求开启但配置关闭。
3. `CONFIDENTIAL` / `SECRET` 被配置为允许第三方 LLM。
4. callback HMAC secret 缺失。
5. 数据库 migration 版本不匹配。
6. 必要队列缺失且配置要求启动时校验。
7. `meeting.chunk.strategy-version` 缺失或为空。

## 7. 验收标准

1. `mvn -pl meeting-api-start -am compile` 通过。
2. 应用可在 local profile 启动。
3. health endpoint 能反映数据库、RabbitMQ、TOS 关键依赖状态。
4. prod profile 缺少关键密钥时启动失败。
5. 组件扫描覆盖 adapter、app、infrastructure，不引入重复 Bean。
