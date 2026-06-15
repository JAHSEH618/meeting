# 后端用户 ID 外键约束修复

## 日期: 2026-06-15

## 问题根源

多个 Controller 使用了可选的 `X-User-Id` header，当该 header 缺失时，使用 `"anonymous"` 作为 fallback：

```java
userId == null || userId.isBlank() ? "anonymous" : userId
```

但是，数据库表中有外键约束 `REFERENCES users(id)`，如果 `users` 表中没有 `id='anonymous'` 的记录，插入时会触发外键约束失败，导致 500 错误。

## 受影响的表

以下表有外键约束指向 `users(id)`：

1. `legal_holds.requested_by` → `REFERENCES users(id)`
2. `deletion_jobs.requested_by` → `REFERENCES users(id)`
3. `export_jobs.created_by` → `REFERENCES users(id)`
4. `rag_query_logs.user_id` → `REFERENCES users(id)`
5. `break_glass_requests.requester_id` → `REFERENCES users(id)` (推测)

## 根本原因

系统已经有 `AuthTenantContextFilter`，它会：
1. 从 `Authorization: Bearer {token}` 中解析 JWT
2. 调用 `AuthFacade.authenticate()` 获取用户信息
3. 通过 `TenantContextHolder.set(tenantId, userId, requestId)` 设置到 ThreadLocal

所以，**正确的做法是从 `TenantContextHolder.currentUserId()` 获取当前认证用户的 ID**，而不是依赖可选的 header。

## 修复的文件

### 1. LegalHoldController.java
- `create()` 方法 (POST /api/legal-holds)
- `delete()` 方法 (DELETE /api/legal-holds/{id})
- `putRelease()` 方法 (PUT /api/legal-holds/{id}/release)
- 移除了 `@RequestHeader(value = "X-User-Id", required = false) String userId` 参数
- 使用 `TenantContextHolder.currentUserId()` 获取当前用户 ID
- 添加了 null 检查：如果用户上下文未设置，抛出 `IllegalStateException`

### 2. DeletionJobController.java
- `create()` 方法 (POST /api/admin/deletion-jobs)
- 同样的修复模式

### 3. ExportController.java
- `create()` 方法 (POST /api/meetings/{meetingId}/exports)
- `cancel()` 方法 (POST /api/exports/{exportId}/cancel)
- `revokeLink()` 方法 (POST /api/exports/{exportId}/revoke-link)
- 同样的修复模式

### 4. BreakGlassController.java
- `create()` 方法 (POST /api/admin/break-glass/requests)
- `approve()` 方法 (POST /api/admin/break-glass/requests/{requestId}/approve)
- `reject()` 方法 (POST /api/admin/break-glass/requests/{requestId}/reject)
- 同样的修复模式

### 5. RagQueryController.java
- `query()` 方法 (POST /api/rag/query)
- 移除了 `effectiveUserId` 变量
- 直接使用 `TenantContextHolder.currentUserId()`
- 用于 rate limiter key 和记录到 `rag_query_logs`

## 修复模式

**之前：**
```java
@PostMapping("/api/some-endpoint")
public ResponseEntity<ApiResponse<DTO>> create(
    @RequestBody RequestBody body,
    @RequestHeader("X-Request-Id") String requestId,
    @RequestHeader("X-Trace-Id") String traceId,
    @RequestHeader(value = "X-User-Id", required = false) String userId
) {
    String effectiveUserId = userId == null || userId.isBlank() ? "anonymous" : userId;
    // 使用 effectiveUserId...
}
```

**之后：**
```java
@PostMapping("/api/some-endpoint")
public ResponseEntity<ApiResponse<DTO>> create(
    @RequestBody RequestBody body,
    @RequestHeader("X-Request-Id") String requestId,
    @RequestHeader("X-Trace-Id") String traceId
) {
    String currentUserId = TenantContextHolder.currentUserId();
    if (currentUserId == null || currentUserId.isBlank()) {
        throw new IllegalStateException("User context is not set — operation requires authentication");
    }
    // 使用 currentUserId...
}
```

## 为什么这样修复是正确的

1. **安全性**：不能让客户端通过 header 随意指定 user ID，必须从已验证的 JWT 中提取
2. **一致性**：`AuthTenantContextFilter` 已经做了这个工作，应该复用而不是重复
3. **数据完整性**：确保 user_id 总是指向 `users` 表中的真实记录
4. **审计追溯**：所有操作都能追溯到真实的认证用户

## E2E 测试影响

修复后，E2E 测试中的 `legal-hold.spec.ts` 应该能通过：
- 测试已经通过 `login()` 获取了 JWT token
- 使用 `Authorization: Bearer ${token}` 发送请求
- `AuthTenantContextFilter` 会解析 token 并设置 `TenantContextHolder`
- Controller 从 `TenantContextHolder.currentUserId()` 获取真实的 user ID
- 不再尝试插入 `"anonymous"` 导致外键约束失败

## 测试验证

1. **单元测试**：需要确保在测试中 mock `TenantContextHolder.currentUserId()` 返回有效的用户 ID
2. **集成测试**：确保 `AuthTenantContextFilter` 正确设置上下文
3. **E2E 测试**：运行 `npm run e2e` 验证 legal hold 流程

## 后续工作

1. **检查其他 Controller**：搜索所有使用 `X-User-Id` header 的地方，确保都正确处理
2. **文档更新**：如果有 API 文档提到 `X-User-Id` header，需要移除
3. **考虑是否需要 anonymous 用户**：如果确实需要支持匿名操作，应该：
   - 在数据库中插入 `id='anonymous'` 的系统用户
   - 或者将外键约束改为可空
   - 但当前系统所有操作都要求认证，所以不需要

## 相关文件

- `AuthTenantContextFilter.java` - JWT 解析和上下文设置
- `TenantContextHolder.java` - ThreadLocal 上下文存储
- `V202605110001__initial_schema.sql` - 数据库表定义和外键约束
