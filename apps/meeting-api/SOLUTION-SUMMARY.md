# P2 Nonce Replay Fix - Summary

## Task Completed ✅

Fixed test failures after P2 implementation of nonce verification in callback security.

## Problem

After P2 added nonce replay protection to `CallbackSecurityVerifier`, two tests failed:
1. `heartbeatUpdatesTaskWithoutCallbackEvent` - ERROR: "callback nonce already used"
2. `updateStepHeartbeatRejectsMismatchedMeetingId` - Wrong exception type

**Root Cause:** Double-verification bug in heartbeat flow:
- `updateStep()` called `securityVerifier.verify()` (records nonce)
- For heartbeats (RUNNING + progress > 0), it then called public `heartbeat()`
- `heartbeat()` called `verify()` again with the same nonce
- Second verify threw "nonce already used (replay attack detected)"

## Solution

Applied the **internal helper pattern** used by other callback methods:

1. **Extracted `heartbeatInternal()` private method** - No verification, just transaction execution
2. **Public `heartbeat()` verifies then delegates** - External callers get full security
3. **`updateStep()` calls `heartbeatInternal()` directly** - Already verified, skip redundant check

**Result:** Single verification per request, correct security semantics maintained.

## Changes

### 1. `ProcessingTaskCallbackApplicationService.java`
- Line 97: Changed `heartbeat(...)` → `heartbeatInternal(...)`
- Line 147: PUBLIC `heartbeat()` now calls `heartbeatInternal(command)` after verify
- Line 150: NEW PRIVATE `heartbeatInternal()` method (no verify, just transaction)

### 2. `ProcessingTaskCallbackApplicationServiceTest.java`
- Added `uniqueNonce()` helper using UUID for unique nonces per test call
- Updated `metadata()` to accept explicit nonce parameter
- Fixed `failReplayWithSameBodyHashIsNoOp` to use different nonces (`nonce_01`, `nonce_02`)
- Fixed `transcriptCallbackReplayWithSameBodyHashIsIdempotent` similarly
- Added parameter comments for clarity

### 3. `meeting-api-domain/pom.xml`
- Added JUnit Jupiter test dependency
- Added AssertJ test dependency

## Verification

### Test Results
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./mvnw clean install -DskipTests
./mvnw -pl meeting-api-start test -Dtest=ProcessingTaskCallbackApplicationServiceTest
```

**Result:** ✅ All 15 tests pass
```
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
```

### Bytecode Verification
```bash
javap -private meeting-api-app/target/classes/.../ProcessingTaskCallbackApplicationService.class
```

Confirmed:
- ✅ Public `heartbeat()` method exists
- ✅ Private `heartbeatInternal()` method exists
- ✅ `updateStep()` bytecode calls `heartbeatInternal` at offset 106

## Security Properties Maintained

1. ✅ External `heartbeat()` calls are fully verified (HMAC + timestamp + nonce)
2. ✅ Internal calls from `updateStep()` skip redundant verification (already verified)
3. ✅ Nonces recorded exactly once per request
4. ✅ Replay attack protection works correctly
5. ✅ Idempotency works (body-hash comparison for functional replays)

## Key Learning

**Maven local repository caching:** After code changes, a full `clean install` was required to properly rebuild and install JARs in `.m2/repository`. The test module was loading stale versions, causing confusion despite correct bytecode in `target/classes`.

## Commit

```
commit a1cfdd9
fix(worker): P2 nonce replay - heartbeat double-verify resolved
```

Branch: `fix/review-remediation-p2-meeting-api`

## Documentation

Created three debugging documents:
1. `DEBUG-P2-NONCE-REPLAY.md` - Complete systematic debugging report following Phase 1-4
2. `VERIFY-FIX.md` - Bytecode verification notes
3. `P2-COMPLETE.md` - (Already existed) P2 completion marker

---

**Status:** ✅ COMPLETE - All tests pass, fix committed, ready for review/merge.
