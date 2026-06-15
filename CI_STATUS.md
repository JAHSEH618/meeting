# CI 当前状态

## 最新提交
- `4b1cccf` - 修复单元测试编译错误
- `cb8371d` - 主要修复：前端 auth refresh + 后端 user ID 外键

## Java 编译 ✅ 应该通过
修复了 `ExportControllerTest` 和 `RagQueryControllerTest` 的方法签名，移除了 `X-User-Id` 参数。

## E2E 测试 ❌ 仍然失败

### 失败的测试
1. **legal-hold.spec.ts** - `releaseResp.ok()` 返回 false
2. **main-flow.spec.ts** - 页面重定向到 `/login`
3. **rag-flow.spec.ts** - 页面重定向到 `/login`

### 问题分析

#### 1. Legal Hold Release 失败
```
PUT /api/legal-holds/{holdId}/release 返回非 2xx
```

可能原因：
- 后端 `release()` 方法现在需要从 `TenantContextHolder.currentUserId()` 获取用户 ID
- E2E 测试通过 `Authorization: Bearer ${token}` 发送请求
- `AuthTenantContextFilter` 应该从 token 中提取用户 ID 并设置到 ThreadLocal
- **但测试可能在 release 步骤失败，需要查看后端日志**

#### 2. Main Flow / RAG Flow - Auth 重定向

页面导航后重定向到登录页，说明：
1. Login 成功（否则第一步就会失败）
2. 创建 meeting 成功
3. 导航到 `/meetings/{id}/transcript` 后触发重定向

**Auth 流程：**
```
1. 导航到新页面
2. React 重新初始化 → authToken = null
3. useAuth() 调用 getCurrentUser()
4. GET /auth/me 返回 401（没有 Authorization header）
5. 触发 refresh 流程
6. refresh() 尝试读取 XSRF-TOKEN cookie
7. 如果 cookie 存在，调用 POST /auth/refresh
8. 如果 refresh 成功，获取新 accessToken
9. 重试 GET /auth/me
```

**可能的失败点：**
- Cookie 在页面导航后丢失（不太可能，cookie 是持久的）
- XSRF-TOKEN cookie 未设置（后端问题）
- Refresh endpoint 返回错误（refresh token 无效/过期）
- Refresh 成功但 retry 仍然失败

### 调试步骤

#### 立即可做：
1. **检查 CI 日志**
   - 查看后端日志，看 PUT /api/legal-holds/{id}/release 为什么失败
   - 查看是否有关于 `TenantContextHolder.currentUserId()` 返回 null 的错误

2. **检查 auth 流程**
   - 后端日志应该显示 POST /auth/login 是否成功
   - 是否设置了 refresh cookie 和 XSRF-TOKEN
   - POST /auth/refresh 是否被调用
   - Refresh 是否成功

#### 可能需要的修复：

**场景 A：Refresh cookie 未设置**
- 检查后端 `POST /api/auth/login` 是否设置了 HttpOnly refresh cookie
- 确认 cookie 的 domain/path/sameSite 配置正确

**场景 B：Refresh 调用失败**
- 添加前端日志：在 `handleUnauthorized` catch 块中 console.error
- 检查是否是 CSRF token 问题

**场景 C：AuthTenantContextFilter 未运行**
- 确认 filter 注册正确
- 确认 JWT 解析逻辑没有问题
- 可能需要在测试环境中 mock 或配置 AuthFacade

**场景 D：后端 release 逻辑问题**
- 检查 `LegalHoldController.release()` 是否正确调用 `TenantContextHolder.currentUserId()`
- 确认没有其他遗留的 `X-User-Id` 依赖

### 建议的下一步

1. **等待 CI 完成，查看完整日志**
   - Java 编译应该通过
   - 重点看后端运行时日志

2. **如果后端日志显示 `currentUserId()` 返回 null**
   - 说明 `AuthTenantContextFilter` 没有正确设置上下文
   - 需要检查 filter 配置和 JWT 解析

3. **如果前端测试显示 refresh 失败**
   - 添加详细日志到 `handleUnauthorized` 和 `refresh()`
   - 确认 cookie 是否正确设置

4. **临时解决方案（如果紧急）**
   - 在 E2E 测试中使用 Playwright 的 `page.evaluate()` 在导航前保存 token 到 sessionStorage
   - 修改前端代码在 sessionStorage 中也保存一份 token 作为 fallback（仅用于测试）

### 相关文件
- `E2E_FIX_SUMMARY.md` - 修复详情
- `BACKEND_USER_ID_FIX.md` - 后端修复说明
- 前端：`apps/meeting-web/src/shared/api/client.ts`
- 后端：`apps/meeting-api/meeting-api-adapter/src/main/java/com/meeting/api/adapter/auth/AuthTenantContextFilter.java`
