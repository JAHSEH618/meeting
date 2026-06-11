# Phase K测试清理设计规格

> **创建日期**: 2026-06-12  
> **状态**: 设计完成  
> **作者**: Claude Code  
> **目标**: 修复Phase K删除SecurityLevel后的测试编译错误，恢复master可构建状态

---

## 一、问题背景

### 1.1 现状

Phase K (commits e5ac5ff..794a23b) 成功删除SecurityLevel枚举和相关功能：
- ✅ domain/app层已清理完毕
- ✅ 主代码编译通过 (`./mvnw compile` SUCCESS)
- ❌ 测试代码未同步更新，导致编译失败

**影响范围**：
- 21个测试文件
- ~200个编译错误
- 主要错误：SecurityLevel符号缺失、SecurityLevelBlockedException类缺失

### 1.2 约束

- ✅ 不能回滚Phase K或TOS提交（已推送到master）
- ✅ 必须保持Phase K设计意图（移除SecurityLevel）
- ✅ 仅修改测试代码，主代码不变

---

## 二、架构设计

### 2.1 修复策略

**核心原则**：测试代码应反映当前系统状态，不应依赖已删除的功能。

**分类修复**：

| 类型 | 文件数 | 修复方式 |
|---|---|---|
| Type A: SecurityLevel枚举引用 | 18 | 删除import，移除参数 |
| Type B: SecurityLevelBlockedException | 2 | 删除相关测试方法 |
| Type C: 枚举一致性检查 | 1 | 从检查列表移除SecurityLevel |

### 2.2 受影响文件列表

```
meeting-api-start/src/test/java/com/meeting/api/
├── AudioUploadApplicationServiceTest.java          [Type A]
├── ChunkingApplicationServiceTest.java             [Type A]
├── ClientEnumConsistencyTest.java                  [Type C]
├── DashScopeLlmGatewayTest.java                    [Type A + B]
├── DocumentApplicationServiceTest.java             [Type A]
├── DocumentDeletionExecutorTest.java               [Type A]
├── DocxExportGatewayTest.java                      [Type A]
├── EmbeddingTaskDispatcherTest.java                [Type A]
├── ExportRenderServiceTest.java                    [Type A]
├── InMemoryMeetingRepositoryTest.java              [Type A]
├── InMemoryRagAnswerCacheTest.java                 [Type A]
├── JdbcKnowledgeChunkRepositoryIT.java             [Type A]
├── MarkdownExportGatewayTest.java                  [Type A]
├── MeetingApplicationServiceTest.java              [Type A]
├── MeetingControllerAdviceSecurityTest.java        [Type B]
├── MeetingControllerTest.java                      [Type A]
├── MeetingDocumentApplicationServiceTest.java      [Type A]
├── MeetingTestFactory.java                         [Type A]
├── MinutesApplicationServiceTest.java              [Type A]
├── RagAuthorizationServiceTest.java                [Type A + B]
└── RagQueryApplicationServiceTest.java             [Type A]
```

---

## 三、实施方案

### 3.1 Type A修复模式

**问题**：测试代码仍引用SecurityLevel枚举

**修复步骤**：

1. **删除import**
```java
// 删除
import com.meeting.api.client.enums.SecurityLevel;
```

2. **移除构造器参数**
```java
// 修改前
Meeting.Builder()
    .securityLevel(SecurityLevel.INTERNAL)
    .build();

// 修改后
Meeting.Builder()
    .build();
```

3. **修复方法调用**
```java
// 修改前
meeting(SecurityLevel.PUBLIC)

// 修改后
meeting()
```

4. **修复方法定义**
```java
// 修改前
private static Meeting meeting(SecurityLevel level) { ... }

// 修改后
private static Meeting meeting() { ... }
```

### 3.2 Type B修复模式

**问题**：测试SecurityLevel阻塞功能的测试方法

**修复步骤**：

**DashScopeLlmGatewayTest.java**：
删除以下测试方法：
- `void secretLevelBlocksLlmCalls()`
- `void confidentialLevelBlocksLlmCalls()`
- 相关的SecurityLevelBlockedException断言

**MeetingControllerAdviceSecurityTest.java**：
删除：
- `void handleSecurityLevelBlocked()`测试方法

**RagAuthorizationServiceTest.java**：
- 移除SecurityLevel相关的权限测试场景

### 3.3 Type C修复模式

**ClientEnumConsistencyTest.java**：

```java
// 修改前
@Test
void allClientEnumsShouldMatchContracts() {
    checkEnum(SecurityLevel.class, "securityLevel");
    checkEnum(MeetingStatus.class, "meetingStatus");
    // ...
}

// 修改后
@Test
void allClientEnumsShouldMatchContracts() {
    checkEnum(MeetingStatus.class, "meetingStatus");
    // ...
}
```

---

## 四、批量处理脚本

```bash
#!/bin/bash
cd apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api

# Phase 1: 批量清理所有文件
for f in *.java; do
    # 删除SecurityLevel import
    sed -i '' '/import.*SecurityLevel/d' "$f"
    
    # 移除.securityLevel()方法调用
    sed -i '' 's/\.securityLevel(SecurityLevel\.[A-Z_]*)//g' "$f"
    sed -i '' 's/\.securityLevel([^)]*securityLevel())//g' "$f"
    
    # 修复meeting()方法调用
    sed -i '' 's/meeting(SecurityLevel\.[A-Z_]*)/meeting()/g' "$f"
    
    # 修复方法定义
    sed -i '' 's/private static Meeting meeting(SecurityLevel [a-z]*)/private static Meeting meeting()/' "$f"
done

echo "Phase 1: Batch cleanup complete"

# Phase 2: 手工修复特殊case
# - DashScopeLlmGatewayTest: 删除3个测试方法
# - ClientEnumConsistencyTest: 移除SecurityLevel检查
# - MeetingControllerAdviceSecurityTest: 删除handleSecurityLevelBlocked

echo "Phase 2: Manual fixes required for:"
echo "  - DashScopeLlmGatewayTest.java"
echo "  - ClientEnumConsistencyTest.java"
echo "  - MeetingControllerAdviceSecurityTest.java"
```

---

## 五、验证清单

- [ ] 所有测试文件编译通过：`./mvnw test-compile`
- [ ] 主代码仍然编译通过：`./mvnw compile`
- [ ] 无SecurityLevel残留引用：`grep -r "SecurityLevel" src/test/`
- [ ] 单元测试可运行：`./mvnw test -q`

---

## 六、风险评估

### 6.1 低风险区域

- ✅ 仅修改测试代码
- ✅ 主代码已验证正确
- ✅ 删除的测试场景已无意义（功能已删除）

### 6.2 可接受的测试覆盖损失

**删除的测试场景**：
- SecurityLevel.SECRET/CONFIDENTIAL阻塞LLM调用
- SecurityLevel阻塞异常处理
- SecurityLevel枚举一致性检查

**理由**：Phase K设计决策已移除这些功能，测试应同步删除。

### 6.3 无风险

- ❌ 不涉及domain/app/infrastructure层
- ❌ 不影响运行时行为
- ❌ 不改变API契约

---

## 七、实施时间线

| 阶段 | 耗时 | 产出 |
|---|---|---|
| Phase 1: 批量清理 | 5分钟 | 18个文件自动修复 |
| Phase 2: 手工修复 | 15分钟 | 3个特殊文件修复 |
| Phase 3: 验证 | 5分钟 | 编译通过，测试通过 |
| **总计** | **25分钟** | master恢复可构建 |

---

## 八、后续考虑

### 8.1 立即行动

修复测试编译错误，恢复master可构建。

### 8.2 未来优化

- CI增加测试编译检查（防止类似问题）
- 考虑引入契约测试框架
- 评估是否需要补充其他测试场景

---

## 九、决策记录

**决策**：采用测试清理方案，不回滚Phase K

**理由**：
1. Phase K领域模型重构正确（主代码编译通过）
2. 测试代码应反映当前系统状态
3. 回滚风险高且破坏已集成的TOS功能
4. 清理测试是最安全、最快速的修复路径

**权衡**：
- ✅ 快速恢复构建能力
- ✅ 保持Phase K设计完整性
- ⚠️ 损失部分测试场景（已无意义）
