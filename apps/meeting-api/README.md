# meeting-api

Java 17 + Spring Boot + COLA-V5 模块化单体。

详细工程规格见 [`SPEC.md`](SPEC.md)。

> **本项目只支持 JDK 17（Maven enforcer 范围 `[17,18)`）。** 如果本机默认是 JDK 21，先设置 `JAVA_HOME`，例如 macOS：`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。

Maven 模块：

```text
meeting-api-start
meeting-api-client
meeting-api-adapter
meeting-api-app
meeting-api-domain
meeting-api-infrastructure
```

业务域作为各模块内 package 边界：`api/bff`、`user-auth`、`meeting`、`task`、`storage`、`llm-gateway`、`speaker`、`rag`、`document`、`export`、`audit`。

## 本地命令

### 构建与运行

```bash
# 编译
./mvnw -pl meeting-api-start -am compile

# 完整构建（跳过测试）
./mvnw clean package -DskipTests

# 运行（JDK 17 required）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)  # macOS
java -jar meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar
```

默认端口：`8080`。

### 测试与验证（每个阶段完成后必跑）

```bash
# 单元测试 + ArchUnit（无需 Docker）
./mvnw test

# 完整验证：单元 + 集成测试（需要 Docker daemon）
./mvnw verify

# 单模块测试
./mvnw -pl meeting-api-app test
./mvnw -pl meeting-api-domain test -Dtest=MeetingTest

# 类型检查（Java 无需单独命令，编译即检查）
./mvnw compile
```

**CI 门禁命令：** `./mvnw verify -q`

已初始化的最小链路：

```text
meeting-api-adapter       /api/meetings REST API、/internal callback 占位
meeting-api-app           MeetingApplicationService
meeting-api-domain        Meeting 聚合与 MeetingRepository 端口
meeting-api-infrastructure InMemoryMeetingRepository，占位替代 PostgreSQL 实现
meeting-api-start         Spring Boot 启动类与基础配置
```

后续接入数据库时，将 `InMemoryMeetingRepository` 替换为 PostgreSQL RepositoryImpl，并以 `meeting-api-infrastructure/src/main/resources/db/migration/*.sql` 的 Flyway migration 作为运行时 schema 事实源。`docs/ddls/001_initial_schema.sql` 只保留为评审快照。

## 子项目规格

| 子项目 | 规格 |
|---|---|
| meeting-api-start | [`meeting-api-start/SPEC.md`](meeting-api-start/SPEC.md) |
| meeting-api-client | [`meeting-api-client/SPEC.md`](meeting-api-client/SPEC.md) |
| meeting-api-adapter | [`meeting-api-adapter/SPEC.md`](meeting-api-adapter/SPEC.md) |
| meeting-api-app | [`meeting-api-app/SPEC.md`](meeting-api-app/SPEC.md) |
| meeting-api-domain | [`meeting-api-domain/SPEC.md`](meeting-api-domain/SPEC.md) |
| meeting-api-infrastructure | [`meeting-api-infrastructure/SPEC.md`](meeting-api-infrastructure/SPEC.md) |
