# C3 401 Refresh Flow - Smoke Test Checklist

**Date:** 2026-06-13  
**Component:** apps/meeting-web  
**Feature:** Automatic token refresh on 401 with CSRF protection

## Prerequisites

- [ ] Backend running with P2.8 refresh endpoint enabled
- [ ] meeting-web dev server running (`npm run dev`)
- [ ] Browser DevTools open (Application → Cookies, Network tab)
- [ ] Test credentials available (admin/admin123)

## Test Cases

### TC1: Automatic Refresh on 401
**Objective:** Verify automatic token refresh on access token expiry

**Steps:**
1. Login with admin/admin123
2. Open DevTools → Application → Cookies
3. Verify `REFRESH_TOKEN` (HttpOnly) and `XSRF-TOKEN` cookies exist
4. Note access token is stored in memory only (not in localStorage)
5. Wait for access token to expire OR manually expire it in backend
6. Navigate to /meetings (triggers API call)
7. Open Network tab, verify `/auth/refresh` request appears
8. Verify refresh request includes:
   - `X-CSRF-Token` header (matches XSRF-TOKEN cookie value)
   - `REFRESH_TOKEN` cookie sent automatically
9. Verify original /meetings request retries automatically
10. Verify page loads successfully with refreshed token

**Expected Result:**
- ✅ Refresh happens automatically without user intervention
- ✅ Original request succeeds after refresh
- ✅ No redirect to login page
- ✅ User session continues seamlessly

**Actual Result:**
- Status: ⏳ Pending / ✅ PASS / ❌ FAIL
- Notes: 

---

### TC2: Logout Button Functionality
**Objective:** Verify logout button clears state and redirects

**Steps:**
1. Ensure logged in (complete TC1 first)
2. Locate logout button in Shell navigation (left sidebar)
3. Click logout button
4. Verify confirmation dialog appears: "确定要退出登录吗？"
5. Click "确定" (OK) in dialog
6. Observe browser behavior

**Expected Result:**
- ✅ Confirmation dialog appears before logout
- ✅ After confirmation, redirect to /login
- ✅ Both cookies cleared (check DevTools → Application → Cookies)
- ✅ Access token cleared from memory
- ✅ Subsequent API calls fail with 401 (no refresh attempted)

**Actual Result:**
- Status: ⏳ Pending / ✅ PASS / ❌ FAIL
- Notes: 

---

### TC3: Refresh Failure Handling
**Objective:** Verify proper handling when refresh token is invalid/expired

**Steps:**
1. Login with admin/admin123
2. Open DevTools → Application → Cookies
3. Manually delete `REFRESH_TOKEN` cookie (simulate expired refresh token)
4. Wait for access token to expire OR manually expire it
5. Navigate to /meetings (triggers API call)
6. Observe behavior

**Expected Result:**
- ✅ `/auth/refresh` attempted but fails (401)
- ✅ Automatic redirect to /login
- ✅ Message or indication that session expired
- ✅ Both cookies cleared
- ✅ User prompted to login again

**Actual Result:**
- Status: ⏳ Pending / ✅ PASS / ❌ FAIL
- Notes: 

---

### TC4: Single-Flight Refresh (Concurrent 401s)
**Objective:** Verify only ONE refresh happens for multiple concurrent 401s

**Steps:**
1. Login with admin/admin123
2. Wait for access token to expire OR manually expire it
3. Quickly trigger multiple API calls (e.g., navigate to multiple pages rapidly)
4. Open Network tab and filter for `/auth/refresh`
5. Count number of refresh requests

**Expected Result:**
- ✅ Only ONE `/auth/refresh` request appears despite multiple 401s
- ✅ All original requests retry successfully after single refresh
- ✅ No duplicate refresh requests
- ✅ No race conditions or inconsistent state

**Actual Result:**
- Status: ⏳ Pending / ✅ PASS / ❌ FAIL
- Notes: 

---

## Summary

**Total Test Cases:** 4  
**Passed:** ___ / 4  
**Failed:** ___ / 4  
**Blocked:** ___ / 4

**Overall Status:** ⏳ Pending / ✅ PASS / ❌ FAIL

**Issues Found:**
- (List any issues discovered during testing)

**Notes:**
- (Additional observations or recommendations)

**Tester Name:** _______________  
**Test Date:** _______________  
**Environment:** Development / Staging / Production
