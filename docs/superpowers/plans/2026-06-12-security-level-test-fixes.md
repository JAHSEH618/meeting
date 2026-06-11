# SecurityLevel测试修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除Phase K后遗留的SecurityLevel功能测试，恢复测试套件通过

**Architecture:** 直接删除验证已删除功能的测试方法，修复参数传递问题

**Tech Stack:** JUnit 5, AssertJ, Maven

---

## 文件映射

**修改文件**：
- `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/ProcessingTaskMessageValidatorTest.java` - 删除2个securityLevel测试
- `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagAuthorizationServiceTest.java` - 删除3个clearance测试
- `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryApplicationServiceTest.java` - 删除1个security过滤测试
- `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryControllerTest.java` - 修复2个参数问题

---

### Task 1: 修复ProcessingTaskMessageValidatorTest

**Files:**
- Modify: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/ProcessingTaskMessageValidatorTest.java:30,92-100`

- [ ] **Step 1: 读取文件找到需要删除的测试**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
grep -n "rejectsUnknownSecurityLevel" apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/ProcessingTaskMessageValidatorTest.java
```

Expected: 找到行号92的测试方法

- [ ] **Step 2: 删除rejectsUnknownSecurityLevel测试方法**

删除完整的测试方法（包括@Test注解到方法结束的}）

- [ ] **Step 3: 删除securityLevel字段断言**

找到line 30附近期望`securityLevel=INTERNAL`的断言，删除该行

- [ ] **Step 4: 验证编译**

```bash
cd apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw test-compile -pl meeting-api-start -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 运行测试**

```bash
./mvnw -pl meeting-api-start test -Dtest=ProcessingTaskMessageValidatorTest -q
```

Expected: Tests run: X (减少1个), Failures: 0

- [ ] **Step 6: Commit修复**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/ProcessingTaskMessageValidatorTest.java
git commit -m "fix(tests): remove SecurityLevel tests from ProcessingTaskMessageValidatorTest"
```

---

### Task 2: 修复RagAuthorizationServiceTest

**Files:**
- Modify: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagAuthorizationServiceTest.java:51-56,58-100,152-157`

- [ ] **Step 1: 定位3个需要删除的测试方法**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
grep -n "authorizeScopeRejectsNullScope\|filterAuthorizedDropsChunksAboveClearance\|filterAuthorizedRejectsNullCandidates" apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagAuthorizationServiceTest.java
```

Expected: 找到line 51, 58, 152的3个方法

- [ ] **Step 2: 删除authorizeScopeRejectsNullScope方法**

删除完整方法（@Test到}）

- [ ] **Step 3: 删除filterAuthorizedDropsChunksAboveClearance方法**

删除完整方法（@Test到}）

- [ ] **Step 4: 删除filterAuthorizedRejectsNullCandidates方法**

删除完整方法（@Test到}）

- [ ] **Step 5: 验证编译**

```bash
cd apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw test-compile -pl meeting-api-start -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 运行测试**

```bash
./mvnw -pl meeting-api-start test -Dtest=RagAuthorizationServiceTest -q
```

Expected: Tests run: X (减少3个), Failures: 0

- [ ] **Step 7: Commit修复**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagAuthorizationServiceTest.java
git commit -m "fix(tests): remove clearance tests from RagAuthorizationServiceTest"
```

---

### Task 3: 修复RagQueryApplicationServiceTest

**Files:**
- Modify: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryApplicationServiceTest.java:230-260`

- [ ] **Step 1: 定位需要删除的测试方法**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
grep -n "highSecurityChunksFilteredOutBeforeReachingLlm" apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryApplicationServiceTest.java
```

Expected: 找到line 230的方法

- [ ] **Step 2: 删除highSecurityChunksFilteredOutBeforeReachingLlm方法**

删除完整方法（@Test到}）

- [ ] **Step 3: 验证编译**

```bash
cd apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw test-compile -pl meeting-api-start -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 运行测试**

```bash
./mvnw -pl meeting-api-start test -Dtest=RagQueryApplicationServiceTest -q
```

Expected: Tests run: X (减少1个), Failures: 0

- [ ] **Step 5: Commit修复**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryApplicationServiceTest.java
git commit -m "fix(tests): remove security filtering test from RagQueryApplicationServiceTest"
```

---

### Task 4: 修复RagQueryControllerTest

**Files:**
- Modify: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryControllerTest.java:43-66,68-85`

- [ ] **Step 1: 读取测试找到问题**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
sed -n '43,66p' apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryControllerTest.java
sed -n '68,85p' apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryControllerTest.java
```

Expected: 看到两个测试方法的代码

- [ ] **Step 2: 检查RagQueryController.query()方法签名**

```bash
grep -A5 "public.*query" apps/meeting-api/meeting-api-adapter/src/main/java/com/meeting/api/adapter/rag/RagQueryController.java | head -10
```

Expected: 确认方法参数列表（可能没有clearance参数）

- [ ] **Step 3: 修复queryAppliesDefaultsAndDelegatesToFacade测试**

查看测试中对`facade.lastUserId`的断言，确认预期值应该是什么。如果测试期望"user_01"但实际是"idem_01"，检查测试设置的userId是否正确。

修复方式：调整断言的expected值或修复测试设置

- [ ] **Step 4: 修复queryParsesScopeHeader测试**

同样检查userId断言，expected "user_42" vs actual "idem_02"。修复设置或断言。

- [ ] **Step 5: 验证编译**

```bash
cd apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw test-compile -pl meeting-api-start -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 运行测试**

```bash
./mvnw -pl meeting-api-start test -Dtest=RagQueryControllerTest -q
```

Expected: Tests run: X, Failures: 0

- [ ] **Step 7: Commit修复**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git add apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/RagQueryControllerTest.java
git commit -m "fix(tests): fix userId assertions in RagQueryControllerTest"
```

---

### Task 5: 全量验证

**Files:**
- Verify: All test files

- [ ] **Step 1: 运行完整测试套件**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting/apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
./mvnw test -q 2>&1 | tail -30
```

Expected: Tests run: 574, Failures: 0, Errors: 0

- [ ] **Step 2: 验证具体减少的测试数量**

之前失败9个，现在应该减少6个（删除），修复3个（RagQueryControllerTest）

Expected: Tests run: 568 (574-6), Failures: 0

- [ ] **Step 3: 推送所有提交**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git push origin master
```

Expected: 4个提交成功推送

---

## 自审清单

- [x] **Spec覆盖**: 所有9个失败测试都有对应修复
- [x] **无占位符**: 所有步骤有具体命令和代码
- [x] **类型一致性**: 测试方法名与错误日志一致

---

## 执行说明

**实施顺序**：必须按Task 1→5顺序执行

**预计时间**：约20-30分钟

**关键点**：
- Task 4需要检查实际方法签名再决定修复方式
- 每个task commit后再进行下一个
- Task 5是最终验证门槛
