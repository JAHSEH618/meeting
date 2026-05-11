# meeting-api-start Spec

## 1. 项目定位

`meeting-api-start` 是 Spring Boot 启动模块，负责应用启动、配置装配、profile、健康检查、组件扫描和运行时入口。它不承载业务规则。

## 2. 启动职责

1. 启动 Spring Boot 应用。
2. 装配 adapter、app、domain、infrastructure 模块。
3. 加载配置文件和环境变量。
4. 初始化健康检查。
5. 暴露应用端口，默认 `8080`。
6. 配置日志、trace、metrics。

## 3. 配置项

`application.yml` 至少包含：

```yaml
server:
  port: 8080

app:
  max-audio-duration-hours: 4
  max-audio-file-size-gb: 3
  enabled-security-levels:
    - PUBLIC
    - INTERNAL
  reserved-security-levels:
    - CONFIDENTIAL
    - SECRET

database:
  rls-enabled: true

queue:
  provider: rabbitmq

storage:
  provider: volcengine-tos

llm:
  provider: dashscope
  text-redaction-before-third-party-llm: false
  secret-fail-closed: true

task:
  lease:
    ttlSeconds: 120
    heartbeatIntervalSeconds: 20
  step:
    maxAttempts: 3
```

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

## 6. 启动失败条件

以下情况应 fail fast：

1. 必要环境变量缺失。
2. RLS 要求开启但配置关闭。
3. `CONFIDENTIAL` / `SECRET` 被配置为允许第三方 LLM。
4. callback HMAC secret 缺失。
5. 数据库 migration 版本不匹配。
6. 必要队列缺失且配置要求启动时校验。

## 7. 验收标准

1. `mvn -pl meeting-api-start -am compile` 通过。
2. 应用可在 local profile 启动。
3. health endpoint 能反映数据库、RabbitMQ、TOS 关键依赖状态。
4. prod profile 缺少关键密钥时启动失败。
5. 组件扫描覆盖 adapter、app、infrastructure，不引入重复 Bean。
