# P2.4-P2.7: Data Integrity and Validation Remediation - Implementation Summary

## 完成状态

### ✅ 已完成 (4/9)

#### **I6: Nonce 去重表** ✅
- 创建 `callback_nonces` 表，5分钟 TTL 窗口
- 实现 `CallbackNonceRepository` 域端口
- 实现 `JdbcCallbackNonceRepository` 与 RLS 策略
- 更新 `CallbackSecurityVerifier` 验证 nonce 唯一性
- 更新所有回调服务使用新的 verify 签名
- 添加单元测试验证 nonce 去重逻辑
- **验证链**: HMAC → 时间戳偏移 → nonce 去重 → 幂等性
- **影响**: 防止5分钟窗口内的重放攻击

#### **I7: 心跳 progress 单调守卫** ✅
- 在 `ProcessingTaskStep.heartbeat()` 中拒绝后退的 progress
- progress 回退时保持原值不变
- 租约续期（leaseExpiresAt, attemptNo, leaseOwner）仍然发生
- 添加单元测试覆盖 progress 回退场景
- **影响**: 防止乱序/重复心跳导致的数据损坏，同时确保租约 TTL 正确延长

#### **I10: Embeddings 回调补租约校验** ✅
- 验证 leaseOwner 匹配当前任务租约
- 防止过期/撤销租约的持有者写入 embeddings
- 保留现有的 attemptNo 验证
- **影响**: 防止过期租约的 embeddings 覆盖当前结果

#### **I14: Outbox 同聚合 fencing** ✅ (已存在)
- `OutboxEventStore.nextSequenceNo()` 使用 `FOR UPDATE` 行级锁
- 单调递增分配 `sequence_no`
- **状态**: 已正确实现，无需修改

### 📝 已记录 (1/9)

#### **I13: RAG 缓存键带版本** 📝
- **需求**: RAG 答案缓存键包含 transcriptVersion、chunk strategy version
- **当前缓解**: 显式调用 `invalidateMeeting`/`invalidateDocument` 在 chunk rebuild 时
- **完整实现需要**:
  - 在 `meetings` 表添加 `rag_version` 列
  - 在 `RagQueryCommand` 包含版本字段
  - 更新 `RagCacheKey` 构造函数
  - Transcript 编辑时递增 ragVersion
- **优先级**: 高
- **延期原因**: 需要 schema 变更和跨层修改
- **预估工作量**: 3-4小时

### ❌ 未实施 (4/9)

#### **I8: expectedInputVersion 校验** ❌
- **需求**: 回调时校验 `expectedInputVersion` 字段，检查 meeting 的版本
- **阻塞**: 
  - `processing_tasks` 表有 `expected_input_version` 列，但域对象未映射
  - 回调 API 不携带 expectedInputVersion
  - 需要重构域模型以支持版本校验
- **优先级**: 高
- **预估工作量**: 4-6小时（需要域模型重构 + 持久化层更新）

#### **I9: /artifacts 真实持久化** ❌
- **需求**: 实现 `/internal/processing-tasks/{taskId}/artifacts` endpoint
- **当前状态**: 仅返回占位响应 `{accepted: true}`
- **需要**:
  - 创建 `task_artifacts` 表
  - 实现完整的 size/hash/mime-type 校验
  - 持久化逻辑
- **优先级**: 高
- **预估工作量**: 2-3小时

#### **I11: 任务终态回写 meetings.status** ❌
- **需求**: 任务达到终态时更新 `meetings.processing_status`
- **缺失**:
  - `meetings` 表可能缺少 `processing_status` 字段
  - 任务终态→会议状态的同步逻辑
- **优先级**: 中
- **预估工作量**: 2-3小时

#### **I12: 公共写接口幂等键强制** ❌
- **需求**: 审查所有 POST/PUT/PATCH 端点，强制要求 `Idempotency-Key`
- **当前状态**: 内部回调已强制，公共 API 未强制
- **优先级**: 中
- **预估工作量**: 3-4小时（需要审查所有端点）

#### **I15: 回调错误码映射** 🟡 (部分完成)
- **已有**: `MeetingControllerAdvice` 实现了 SPEC §7 的部分映射
- **缺失**: Response envelope 的 `requestId`/`traceId` 当前硬编码为 `null`
- **需要**: 从 MDC 注入实际值
- **优先级**: 中
- **预估工作量**: 1小时

## 提交记录

```
cad80e3 feat(callback): implement nonce deduplication to prevent replay attacks (I6)
ca19d7f feat(task): add progress monotonicity guard for heartbeat callbacks (I7)
fd58645 feat(rag): add lease owner validation for embeddings callback (I10)
e6c9d44 docs(p2): add implementation summary for data integrity tasks (I6-I15)
cf69c2c docs(rag): add TODO for cache key versioning (I13)
```

## 总结

**已完成**: 4/9 任务 (44%)
- ✅ I6: Nonce 去重防止重放攻击
- ✅ I7: Progress 单调守卫防止数据回退
- ✅ I10: Embeddings 租约校验
- ✅ I14: Outbox fencing (已存在)

**已记录待实施**: 1/9 任务 (11%)
- 📝 I13: RAG 缓存版本化 (需要 schema 变更)

**未实施**: 4/9 任务 (45%)
- ❌ I8: expectedInputVersion 校验 (需要域模型重构)
- ❌ I9: /artifacts 真实持久化
- ❌ I11: 会议状态同步
- ❌ I12: 公共 API 幂等键强制
- ❌ I15: MDC 注入 (部分完成)

**关键改进**:
1. 重放攻击窗口已关闭 (I6)
2. 心跳数据一致性得到保护 (I7)
3. 过期租约无法写入embeddings (I10)

**下一步优先级**: I9 > I11 > I12 > I8 > I13 (完整实施) > I15
