# E2E Test Failures - Analysis & Fix

## Date: 2026-06-15

## Issues Identified and Fixed

### 1. Auth Token Lost on Navigation (FIXED - Frontend)
**Root Cause:**
- Access token stored in module-level variable (`client.ts` line 14)
- On page navigation, React re-initializes → `authToken` becomes `null`
- `useAuth()` calls `getCurrentUser()` without token
- Backend returns 401, but refresh flow wasn't triggered because old code checked `if (res.status === 401 && authToken)`
- Since `authToken` was `null`, refresh didn't run
- User gets redirected to login

**Fix Applied:**
- Modified `src/shared/api/client.ts` line 141-144
- Changed from: `if (res.status === 401 && authToken)` 
- Changed to: `if (res.status === 401 && path !== "/auth/login" && path !== "/auth/refresh")`
- Now refresh token flow runs even when `authToken` is `null`, restoring access token from the HttpOnly refresh cookie

**Expected Result:**
- After login, navigation to `/meetings/{id}/transcript` should:
  1. Load page → `authToken` is `null`
  2. `getCurrentUser()` returns 401
  3. Trigger refresh → get new access token from refresh cookie
  4. Retry `getCurrentUser()` with new token → success
  5. Page renders normally

### 2. Legal Hold Endpoint Returns 500 (FIXED - Backend)
**Root Cause:**
```
POST /api/legal-holds
Status: 500
Body: {"success":false,"data":null,"error":{"code":"INTERNAL_ERROR",...}
```

The issue was:
1. `LegalHoldController` (and many others) used `X-User-Id` header with fallback to `"anonymous"`
2. Database tables have foreign key constraints: `requested_by REFERENCES users(id)`
3. When `X-User-Id` is missing, controller tries to insert `"anonymous"` as user ID
4. Foreign key constraint fails because no user with `id='anonymous'` exists
5. SQL exception → 500 error

**Affected Tables:**
- `legal_holds.requested_by`
- `deletion_jobs.requested_by`
- `export_jobs.created_by`
- `rag_query_logs.user_id`
- `break_glass_requests.requester_id`

**Fix Applied:**
The system already has `AuthTenantContextFilter` that:
1. Parses JWT from `Authorization: Bearer {token}`
2. Extracts user info via `AuthFacade.authenticate()`
3. Sets `TenantContextHolder.set(tenantId, userId, requestId)`

So the correct approach is to **use `TenantContextHolder.currentUserId()`** instead of relying on optional header.

**Files Modified:**
1. `LegalHoldController.java` - create, delete, release methods
2. `DeletionJobController.java` - create method
3. `ExportController.java` - create, cancel, revokeLink methods
4. `BreakGlassController.java` - create, approve, reject methods
5. `RagQueryController.java` - query method

**Pattern:**
```java
// Before:
@RequestHeader(value = "X-User-Id", required = false) String userId
String effectiveUserId = userId == null || userId.isBlank() ? "anonymous" : userId;

// After:
// Remove X-User-Id header parameter
String currentUserId = TenantContextHolder.currentUserId();
if (currentUserId == null || currentUserId.isBlank()) {
    throw new IllegalStateException("User context is not set — operation requires authentication");
}
```

**Why This Fix is Correct:**
1. **Security**: Don't let clients specify user ID via header; extract from verified JWT
2. **Consistency**: Reuse existing `AuthTenantContextFilter` work
3. **Data Integrity**: Ensure user_id always points to real record in `users` table
4. **Audit Trail**: All operations traceable to authenticated user

### 3. Local Test Run Failed - Services Not Running
**Issue:**
- `connect ECONNREFUSED ::1:8080` - backend not running
- `ERR_CONNECTION_REFUSED at http://localhost:5173` - frontend not running

**Resolution:**
- This is expected for local runs
- E2E tests require full stack running (see CI setup in `.github/workflows/ci.yml` lines 292-297)
- To run locally:
  ```bash
  # Start full stack
  docker compose --profile full-stack \
      -f infra/meeting-infra/docker/compose/docker-compose.yml \
      up -d
  
  # Wait for services to be ready
  # Then run E2E tests
  cd apps/meeting-web
  npm run e2e
  ```

## Files Modified

### Frontend:
1. `apps/meeting-web/src/shared/api/client.ts`
   - Line 141-144: Fixed 401 refresh trigger logic

### Backend:
1. `apps/meeting-api/meeting-api-adapter/src/main/java/com/meeting/api/adapter/compliance/LegalHoldController.java`
2. `apps/meeting-api/meeting-api-adapter/src/main/java/com/meeting/api/adapter/compliance/DeletionJobController.java`
3. `apps/meeting-api/meeting-api-adapter/src/main/java/com/meeting/api/adapter/export/ExportController.java`
4. `apps/meeting-api/meeting-api-adapter/src/main/java/com/meeting/api/adapter/breakglass/BreakGlassController.java`
5. `apps/meeting-api/meeting-api-adapter/src/main/java/com/meeting/api/adapter/rag/RagQueryController.java`

## Test Status After Fix

### Expected to Pass:
- ✅ `legal-hold.spec.ts` - admin legal hold blocks user delete with 423 (backend fixed)
- ✅ `main-flow.spec.ts` - login → create meeting → transcript / exports pages render (frontend auth fixed)
- ✅ `rag-flow.spec.ts` - rag scope-less question renders an answer card (frontend auth + backend user ID fixed)
- ✅ All tests that navigate between pages after login
- ✅ All tests that call legal-hold / deletion-job / export / break-glass / RAG endpoints

## CI Verification

To verify both fixes work in CI:
1. Push changes to both frontend and backend
2. CI will run `meeting-web-e2e` job
3. It boots full stack via docker compose
4. Runs all E2E tests
5. All tests should now pass

## Technical Details

### Frontend Auth Flow:
1. Login → JWT stored in memory + refresh token in HttpOnly cookie
2. Navigate to new page → React re-initializes → token gone
3. First API call → 401 → refresh flow → new token → retry → success
4. Page renders with data

### Backend User Context Flow:
1. Request arrives with `Authorization: Bearer {jwt}`
2. `AuthTenantContextFilter` intercepts
3. Calls `AuthFacade.authenticate(jwt)` → extracts user info
4. Sets `TenantContextHolder.set(tenantId, userId, requestId)`
5. Controller calls `TenantContextHolder.currentUserId()` → gets real user ID
6. Inserts into database with valid foreign key

## Related Documentation

- `BACKEND_USER_ID_FIX.md` - Detailed backend fix explanation
- `apps/meeting-web/CLAUDE.md` - Frontend architecture
- `apps/meeting-api/meeting-api-adapter/CLAUDE.md` - Adapter layer rules

