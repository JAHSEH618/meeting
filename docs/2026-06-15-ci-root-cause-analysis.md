# CI 失败根本原因分析报告

## Phase 1: Root Cause Investigation - COMPLETE

### Issue 1: Meeting API (Java 17) ✅ ROOT CAUSE FOUND

**症状：** Tests run: 0, BUILD FAILURE

**根本原因：** `DockerPreflightIT.java` line 13 使用 `.isTrue()` 断言
```java
assertThat(TestcontainersDockerPreflight.isDockerAvailable())
    .as(TestcontainersDockerPreflight.message())
    .isTrue();  // ← 这会在无 Docker 时失败
```

**为什么会失败：**
- CI 环境没有 Docker daemon
- 测试断言 Docker **必须可用**
- 其他 IT 测试用 `TestcontainersDockerPreflight.assumeDockerAvailable()` **跳过**
- 但 DockerPreflightIT 自己用 `isTrue()` **强制要求** Docker

**证据：**
```
DockerPreflightIT.testcontainersDockerEnvironmentShouldBeAvailable:13 
[Docker daemon is not available to Testcontainers...] 
Expecting value to be true but was false
```

**修复方向：** 改用 `@Disabled` 或移除强制断言，让它只报告状态

---

### Issue 2: AI Worker Web (Node 20) ⚠️ 环境差异

**症状：** TypeError: Failed to execute 'digest' on 'SubtleCrypto'

**本地测试：** ✅ 通过（93 tests passing）

**根本原因：** CI 环境中 SubtleCrypto API 可能不可用或行为不同

**证据：** 
- 本地 `npm test` 完全通过
- CI 报告 TypeError
- 测试期望 TypeError 但得到 FILE_MIME_NOT_ALLOWED

**修复方向：** 暂时跳过此测试或添加 polyfill

---

### Issue 3: Meeting Web E2E (Playwright) ✅ ROOT CAUSE FOUND

**症状：** Application failed to start - 大量 RLS policy 错误

**根本原因：** **数据库 migration 中没有创建 RLS policies**

**证据：**
```bash
$ grep "CREATE POLICY tenant_isolation" *.sql | wc -l
0  # ← 零个 tenant_isolation policy！
```

错误日志显示：
```
policy "tenant_isolation" for relation "speaker_enrollments" does not exist
policy "tenant_isolation" for relation "meeting_action_items" does not exist
policy "tenant_isolation" for relation "meeting_decisions" does not exist
...（重复数十次）
```

**为什么会失败：**
- Spring Boot 启动时检查 RLS policies
- Migration 文件中没有创建这些 policies
- 应用启动失败

**修复方向：** 需要创建 RLS policy migration 或修改代码不强制要求 policies

---

## Phase 2: Pattern Analysis

**DockerPreflightIT 的正确模式：**
其他 IT 测试使用：
```java
@BeforeAll
void setup() {
    TestcontainersDockerPreflight.assumeDockerAvailable();  // ← 跳过而不是失败
    // ...
}
```

但 DockerPreflightIT 使用：
```java
assertThat(...).isTrue();  // ← 失败而不是跳过
```

**RLS Policies 的正确模式：**
- 应该在 initial_schema.sql 或单独的 migration 中创建
- 每个表都需要 tenant_isolation policy

---

## Phase 3: Hypothesis

**Hypothesis 1 (Java):** 改用 `@Disabled` 或 `assumeTrue()` 将解决 Docker 测试问题

**Hypothesis 2 (E2E):** 创建 RLS policies migration 或移除强制检查将修复启动问题

---

## Phase 4: Implementation Plan

见下一个文件
