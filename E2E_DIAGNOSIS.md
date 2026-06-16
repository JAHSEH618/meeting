# E2E 失败诊断 - 最新状态

## 提交历史
- `f1f8767` - 添加详细的 auth 和 E2E 诊断日志
- `4b1cccf` - 修复单元测试
- `cb8371d` - 主要修复（auth refresh + user ID）

## 当前问题

### 症状
所有 E2E 测试失败，页面被重定向到 `/login`

### 失败模式

#### 1. main-flow + rag-flow 测试
**现象：**
- 登录成功
- 创建 meeting 成功
- 导航到 `/meetings/{id}/transcript` 后 → 重定向到 `/login`
- 页面显示登录页 HTML，找不到目标元素

**预期流程：**
```
1. Login → 获取 access token (内存) + refresh token (HttpOnly cookie)
2. 导航到新页面 → React 重新初始化 → access token = null
3. useAuth() → getCurrentUser() → 401
4. handleUnauthorized() → refresh() → 获取新 access token
5. 重试 getCurrentUser() → 成功
6. 页面正常渲染
```

**可能的断点：**
- ❓ Refresh cookie 未设置
- ❓ CSRF token 未设置
- ❓ Refresh 返回错误
- ❓ Refresh 成功但 retry 仍然失败

#### 2. legal-hold 测试
**现象：**
- `PUT /api/legal-holds/{holdId}/release` 返回非 2xx

**可能原因：**
- ❓ Backend `TenantContextHolder.currentUserId()` 返回 null
- ❓ Admin token 未正确传递
- ❓ HTTP 方法不匹配
- ❓ 权限验证失败

## 已添加的诊断

### Frontend (client.ts)
```javascript
// handleUnauthorized()
console.log('[Auth] handleUnauthorized triggered for', method, path);
console.log('[Auth] Refresh already in-flight, awaiting...');
console.log('[Auth] Starting new refresh...');
console.log('[Auth] Refresh completed, retrying request');
console.error('[Auth] Refresh failed:', err);

// refresh()
console.log('[Auth] refresh() called');
console.log('[Auth] CSRF token found:', !!csrfToken);
console.log('[Auth] All cookies:', document.cookie);
console.log('[Auth] Calling POST /auth/refresh');
console.log('[Auth] Refresh response status:', res.status);
console.error('[Auth] Refresh failed with error:', json.error);
console.log('[Auth] Refresh succeeded');
```

### E2E (legal-hold.spec.ts)
```typescript
if (!releaseResp.ok()) {
  console.error(`[E2E DIAGNOSTIC] PUT /api/legal-holds/${holdId}/release failed:`);
  console.error(`  Status: ${status}`);
  console.error(`  Body: ${body.substring(0, 500)}`);
}
```

## 下一步 CI 日志分析

### 需要查找的信息

#### 1. 前端日志（浏览器 console）
- [ ] `[Auth] handleUnauthorized` 是否被触发？
- [ ] `[Auth] CSRF token found: true/false`？
- [ ] `[Auth] All cookies:` 显示了什么？
- [ ] `[Auth] Refresh response status:` 返回了什么？
- [ ] `[Auth] Refresh failed with error:` 错误详情？

#### 2. 后端日志
- [ ] POST /auth/login 是否成功？返回了什么？
- [ ] Set-Cookie header 是否包含 refresh token 和 XSRF-TOKEN？
- [ ] POST /auth/refresh 是否被调用？
- [ ] Refresh 返回了什么状态码？
- [ ] PUT /api/legal-holds/{id}/release 为什么失败？
- [ ] `AuthTenantContextFilter` 是否正确设置了用户上下文？

### 可能的发现和对应修复

#### 场景 A：CSRF token 未找到
**日志特征：**
```
[Auth] CSRF token found: false
[Auth] All cookies: (empty or no XSRF-TOKEN)
```

**原因：** Backend login 未设置 XSRF-TOKEN cookie

**修复：** 检查后端 auth controller，确保设置了 XSRF-TOKEN

#### 场景 B：Refresh 返回 401
**日志特征：**
```
[Auth] Refresh response status: 401
[Auth] Refresh failed with error: {...}
```

**原因：** Refresh token 无效/过期，或 backend 未正确验证

**修复：** 检查后端 refresh endpoint 实现和 refresh token 验证逻辑

#### 场景 C：Refresh 成功但 retry 仍 401
**日志特征：**
```
[Auth] Refresh succeeded
[Auth] handleUnauthorized triggered for GET /auth/me (second time)
```

**原因：** 可能陷入无限循环，或新 token 仍然无效

**修复：** 添加 retry 计数器，防止无限循环

#### 场景 D：Cookie domain/path 不匹配
**日志特征：**
```
[Auth] All cookies: (没有相关 cookie)
```

**原因：** Cookie 的 domain/path/SameSite 配置导致浏览器不发送

**修复：** 调整后端 cookie 设置：
- domain: localhost（或留空）
- path: /
- SameSite: Lax 或 None（如果跨域）
- Secure: false（如果是 http）

#### 场景 E：TenantContextHolder.currentUserId() 为 null
**日志特征：**
```
PUT /api/legal-holds/{id}/release failed:
Status: 500
Body: ... User context is not set ...
```

**原因：** `AuthTenantContextFilter` 未运行或 JWT 解析失败

**修复：**
1. 确认 filter 注册顺序正确
2. 检查 JWT 格式和签名验证
3. 确认 E2E 测试传递的是有效 JWT

## 临时 Workaround（如果需要）

如果 refresh 流程太复杂，可以临时：

1. **在 E2E 中使用 storage event 保持 token：**
```typescript
await page.evaluate((token) => {
  window.addEventListener('beforeunload', () => {
    sessionStorage.setItem('_e2e_token', token);
  });
}, accessToken);

// 导航后恢复
await page.goto(url);
await page.evaluate(() => {
  const token = sessionStorage.getItem('_e2e_token');
  if (token) {
    // 调用某个 window.restoreAuth(token) 方法
  }
});
```

2. **修改前端在测试环境使用 sessionStorage：**
```typescript
// 仅在 E2E 环境
if (import.meta.env.MODE === 'test') {
  const savedToken = sessionStorage.getItem('access_token');
  if (savedToken) authToken = savedToken;
}
```

但这些都是 workaround，不应该在生产代码中保留。

## 相关文件

- `apps/meeting-web/src/shared/api/client.ts` - Auth 逻辑
- `apps/meeting-web/src/services/auth.ts` - useAuth hook
- `apps/meeting-web/e2e/tests/legal-hold.spec.ts` - Legal hold E2E
- `apps/meeting-api/.../auth/AuthTenantContextFilter.java` - JWT 解析
- `apps/meeting-api/.../compliance/LegalHoldController.java` - Release endpoint
