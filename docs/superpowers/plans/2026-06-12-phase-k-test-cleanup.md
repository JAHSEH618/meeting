# Phase K测试清理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复Phase K删除SecurityLevel后的21个测试文件编译错误，恢复master可构建状态

**Architecture:** 分两阶段执行 - Phase 1批量清理SecurityLevel引用，Phase 2手工修复3个特殊测试文件

**Tech Stack:** Java 17, Maven, sed批量处理, JUnit 5

---

## 文件映射

**批量修复** (18个文件，Phase 1):
- `meeting-api-start/src/test/java/com/meeting/api/AudioUploadApplicationServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/ChunkingApplicationServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/DocumentApplicationServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/DocumentDeletionExecutorTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/DocxExportGatewayTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/EmbeddingTaskDispatcherTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/ExportRenderServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/InMemoryMeetingRepositoryTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/InMemoryRagAnswerCacheTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/JdbcKnowledgeChunkRepositoryIT.java`
- `meeting-api-start/src/test/java/com/meeting/api/MarkdownExportGatewayTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/MeetingApplicationServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/MeetingControllerTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/MeetingDocumentApplicationServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/MeetingTestFactory.java`
- `meeting-api-start/src/test/java/com/meeting/api/MinutesApplicationServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/RagAuthorizationServiceTest.java`
- `meeting-api-start/src/test/java/com/meeting/api/RagQueryApplicationServiceTest.java`

**手工修复** (3个文件，Phase 2):
- `meeting-api-start/src/test/java/com/meeting/api/DashScopeLlmGatewayTest.java` - 删除SecurityLevel阻塞测试方法
- `meeting-api-start/src/test/java/com/meeting/api/ClientEnumConsistencyTest.java` - 从检查列表移除SecurityLevel
- `meeting-api-start/src/test/java/com/meeting/api/MeetingControllerAdviceSecurityTest.java` - 删除handleSecurityLevelBlocked测试

---

### Task 1: 批量清理SecurityLevel引用

**Files:**
- Modify: `meeting-api-start/src/test/java/com/meeting/api/*.java` (18个文件)

- [ ] **Step 1: 执行批量清理脚本**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api

for f in AudioUploadApplicationServiceTest.java ChunkingApplicationServiceTest.java DocumentApplicationServiceTest.java DocumentDeletionExecutorTest.java DocxExportGatewayTest.java EmbeddingTaskDispatcherTest.java ExportRenderServiceTest.java InMemoryMeetingRepositoryTest.java InMemoryRagAnswerCacheTest.java JdbcKnowledgeChunkRepositoryIT.java MarkdownExportGatewayTest.java MeetingApplicationServiceTest.java MeetingControllerTest.java MeetingDocumentApplicationServiceTest.java MeetingTestFactory.java MinutesApplicationServiceTest.java RagAuthorizationServiceTest.java RagQueryApplicationServiceTest.java; do
    sed -i '' '/import.*SecurityLevel/d' "$f"
    sed -i '' 's/\.securityLevel(SecurityLevel\.[A-Z_]*)//g' "$f"
    sed -i '' 's/\.securityLevel([^)]*securityLevel())//g' "$f"
    sed -i '' 's/meeting(SecurityLevel\.[A-Z_]*)/meeting()/g' "$f"
    sed -i '' 's/private static Meeting meeting(SecurityLevel [a-z]*)/private static Meeting meeting()/' "$f"
done
```

Expected: 18个文件已修改，删除所有SecurityLevel import和参数

- [ ] **Step 2: 验证批量清理结果**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw test-compile 2>&1 | grep -c "cannot find symbol.*SecurityLevel"
```

Expected: 剩余错误应小于20个（仅来自3个手工修复文件）

- [ ] **Step 3: Commit批量清理**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/
git commit -m "fix(tests): batch cleanup SecurityLevel references (Phase 1)"
```

---

### Task 2: 修复DashScopeLlmGatewayTest

**Files:**
- Modify: `meeting-api-start/src/test/java/com/meeting/api/DashScopeLlmGatewayTest.java`

- [ ] **Step 1: 查找SecurityLevel阻塞测试方法**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api
grep -n "secretLevelBlocksLlmCalls\|confidentialLevelBlocksLlmCalls" DashScopeLlmGatewayTest.java
```

Expected: 找到2个测试方法的行号

- [ ] **Step 2: 删除SecurityLevel阻塞测试方法**

查找并删除以下测试方法（包括@Test注解）：
- `void secretLevelBlocksLlmCalls()`
- `void confidentialLevelBlocksLlmCalls()`

以及删除所有SecurityLevelBlockedException相关代码

- [ ] **Step 3: 验证编译**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw -pl meeting-api-start test-compile -Dtest=DashScopeLlmGatewayTest -q 2>&1 | tail -5
```

Expected: DashScopeLlmGatewayTest编译通过

- [ ] **Step 4: Commit修复**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/DashScopeLlmGatewayTest.java
git commit -m "fix(tests): remove SecurityLevel blocking tests from DashScopeLlmGatewayTest"
```

---

### Task 3: 修复ClientEnumConsistencyTest

**Files:**
- Modify: `meeting-api-start/src/test/java/com/meeting/api/ClientEnumConsistencyTest.java`

- [ ] **Step 1: 查找SecurityLevel检查代码**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api
grep -n "checkEnum.*SecurityLevel" ClientEnumConsistencyTest.java
```

Expected: 找到checkEnum(SecurityLevel.class, ...)调用

- [ ] **Step 2: 删除SecurityLevel检查行**

删除包含`checkEnum(SecurityLevel.class, "securityLevel")`的行

- [ ] **Step 3: 验证编译**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw -pl meeting-api-start test-compile -Dtest=ClientEnumConsistencyTest -q 2>&1 | tail -5
```

Expected: ClientEnumConsistencyTest编译通过

- [ ] **Step 4: Commit修复**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/ClientEnumConsistencyTest.java
git commit -m "fix(tests): remove SecurityLevel from enum consistency checks"
```

---

### Task 4: 修复MeetingControllerAdviceSecurityTest

**Files:**
- Modify: `meeting-api-start/src/test/java/com/meeting/api/MeetingControllerAdviceSecurityTest.java`

- [ ] **Step 1: 查找handleSecurityLevelBlocked测试**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api
grep -n "handleSecurityLevelBlocked" MeetingControllerAdviceSecurityTest.java
```

Expected: 找到测试方法行号

- [ ] **Step 2: 删除handleSecurityLevelBlocked测试方法**

删除整个测试方法（包括@Test注解）

- [ ] **Step 3: 验证编译**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw -pl meeting-api-start test-compile -Dtest=MeetingControllerAdviceSecurityTest -q 2>&1 | tail -5
```

Expected: MeetingControllerAdviceSecurityTest编译通过

- [ ] **Step 4: Commit修复**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/MeetingControllerAdviceSecurityTest.java
git commit -m "fix(tests): remove handleSecurityLevelBlocked test"
```

---

### Task 5: 全量验证

**Files:**
- Verify: 所有测试文件

- [ ] **Step 1: 编译所有测试**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw test-compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 检查残留SecurityLevel引用**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api
grep -r "SecurityLevel" *.java | wc -l
```

Expected: 0

- [ ] **Step 3: 运行快速测试抽查**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw -pl meeting-api-start test -Dtest=ExtractionApplicationServiceTest -q 2>&1 | grep -E "Tests run|BUILD"
```

Expected: Tests run: X, BUILD SUCCESS

- [ ] **Step 4: 推送到远程**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git push origin master
```

Expected: 4个提交成功推送

---

## 自审清单

- [x] **Spec覆盖**: 所有21个文件已映射
- [x] **无占位符**: 所有sed命令、文件路径、验证命令完整
- [x] **类型一致性**: Meeting.Builder()调用模式一致
- [x] **批量+手工**: Phase 1批量18个，Phase 2手工3个

---

## 执行说明

**实施顺序**：必须按Task 1→5顺序执行（批量清理先行，手工修复随后）

**预计时间**：20-30分钟

**关键点**：
- Task 1批量脚本一次性清理18个文件
- Task 2-4手工删除特定测试方法
- Task 5验证master完全可构建
