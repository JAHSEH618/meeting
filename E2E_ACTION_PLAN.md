# E2E 修复行动计划

## 基于 Superpowers 分析的结论

### 核心问题确认
从日志明确看到：**测试被重定向到 `/login`，不是元素没找到**

### 根因分析（按优先级）

#### 优先级 1：认证状态丢失（影响 main-flow + rag-flow）

**断定：** 登录成功了（创建 meeting 成功），但页面导航后认证状态没有恢复

**立即检查项：**
1. ✅ 已添加日志：查看 CSRF token 是否存在
2. ✅ 已添加日志：查看 refresh 是否被调用
3. ✅ 已添加日志：查看 refresh 返回什么错误
4. ⏳ 等待 CI：查看实际日志输出

**最可能原因（按概率排序）：**
1. **Cookie 在 CI 环境未被设置/保持** (80%)
   - Backend 可能未返回 Set-Cookie
   - Cookie SameSite/Secure 配置不对
   - Playwright 未正确处理 cookie

2. **CSRF token 缺失** (15%)
   - Backend 未设置 XSRF-TOKEN cookie
   - Cookie 名称不匹配（XSRF-TOKEN vs X-CSRF-TOKEN）

3. **Refresh endpoint 实现问题** (5%)
   - Endpoint 不存在或路径不对
   - Refresh token 验证逻辑错误

#### 优先级 2：Legal Hold Release 失败（独立问题）

**断定：** 接口返回非 2xx，需要看具体状态码和响应体

**立即检查项：**
1. ✅ 已添加日志：下次运行会打印 status + body
2. ⏳ 等待 CI：查看实际错误

**最可能原因（按概率排序）：**
1. **Admin token 认证失败** (40%)
   - 和优先级 1 的问题相同：cookie 丢失
   - Admin context 也受影响

2. **TenantContextHolder.currentUserId() 返回 null** (30%)
   - `AuthTenantContextFilter` 未正确设置上下文
   - JWT 解析失败

3. **HTTP 方法或路径不匹配** (20%)
   - 测试用 PUT，backend 可能是 POST/DELETE
   - URL 路径拼接错误

4. **权限验证失败** (10%)
   - Admin role 未被识别
   - Release 需要特殊权限

## 立即可执行的调试步骤

### 步骤 1：等待当前 CI 完成，查看日志

**期望看到：**
```
[Auth] handleUnauthorized triggered for GET /auth/me
[Auth] refresh() called
[Auth] CSRF token found: false  ← 关键！
[Auth] All cookies: (empty)     ← 关键！
```

或者：
```
[Auth] CSRF token found: true
[Auth] Calling POST /auth/refresh
[Auth] Refresh response status: 401  ← 关键！
[Auth] Refresh failed with error: {...}
```

**Backend 日志期望：**
```
POST /api/auth/login → 200 (检查 Set-Cookie header)
POST /api/auth/refresh → ??? (检查是否被调用)
PUT /api/legal-holds/{id}/release → ??? (检查状态码和错误)
```

### 步骤 2：根据日志结果选择修复方案

#### 情况 A：CSRF token 未找到
→ 检查后端 login endpoint，确保设置 XSRF-TOKEN cookie
→ 检查 cookie 名称是否一致

#### 情况 B：Refresh 返回 401/403
→ 检查后端 refresh endpoint 实现
→ 确认 refresh token 验证逻辑

#### 情况 C：Legal hold 返回 403
→ 检查 admin 权限配置
→ 确认 AdminRole 被正确识别

#### 情况 D：Legal hold 返回 500 "User context is not set"
→ 检查 `AuthTenantContextFilter` 是否运行
→ 确认 JWT 解析逻辑

### 步骤 3：如果 CI 日志不够详细

**添加更多前端日志：**
```typescript
// 在 login 成功后
console.log('[Auth] Login successful, cookies:', document.cookie);

// 在 useAuth useEffect 中
console.log('[Auth] useAuth initializing, ready:', ready);
console.log('[Auth] Current authToken:', !!authToken);
```

**添加 E2E 日志：**
```typescript
// 在 login 后
console.log('Cookies after login:', await page.context().cookies());

// 在导航前
console.log('Before navigation, URL:', await page.url());

// 在导航后
console.log('After navigation, URL:', await page.url());
console.log('Cookies after navigation:', await page.context().cookies());
```

## 快速验证方案（本地）

如果有本地环境，可以快速验证：

```bash
# 1. 启动后端
cd apps/meeting-api
./mvnw spring-boot:run

# 2. 启动前端
cd apps/meeting-web
npm run dev

# 3. 手动测试流程
# 打开浏览器 DevTools
# - 登录
# - 查看 Application → Cookies（应该看到 refresh token 和 XSRF-TOKEN）
# - 导航到 transcript 页面
# - 查看 Console 日志
# - 查看 Network → /auth/me 和 /auth/refresh 请求
```

## 预测和验证

### 预测 1：Cookie 问题（最可能）
**预测：** CI 日志会显示 `CSRF token found: false` 或 `All cookies: (empty)`

**验证：**
1. 检查 backend login 是否返回 Set-Cookie header
2. 检查 cookie 的 domain/path/SameSite/Secure 配置
3. 检查 Playwright 是否正确保存 cookie

**如果确认：**
- 修复 backend cookie 设置
- 或修改 Playwright 配置确保 cookie 保持

### 预测 2：AuthTenantContextFilter 未运行
**预测：** Legal hold release 返回 500 "User context is not set"

**验证：**
1. 检查 filter 是否注册
2. 检查 filter 顺序（应该在 auth 之后）
3. 添加 filter 日志确认是否执行

**如果确认：**
- 修复 filter 配置
- 确保 JWT 解析正确

## 时间估算

- **步骤 1**（等待 CI + 分析日志）: 10-15 分钟
- **步骤 2**（根据日志修复）: 30-60 分钟
- **步骤 3**（如果需要更多调试）: 30-60 分钟
- **验证修复**（重新 CI）: 10-15 分钟

**总计：** 1.5 - 3 小时

## 成功标准

- ✅ main-flow 测试通过（transcript 页面正常显示）
- ✅ rag-flow 测试通过（RAG 页面正常显示）
- ✅ legal-hold 测试通过（release 返回 2xx）
- ✅ 前端日志显示 refresh 成功
- ✅ Backend 日志无错误
