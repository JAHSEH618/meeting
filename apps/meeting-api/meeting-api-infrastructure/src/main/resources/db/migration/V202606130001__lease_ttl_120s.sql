-- P2.1: Lease model rework
-- Changes:
-- 1. Tasks are created in QUEUED state without pre-claiming lease
-- 2. First worker callback claims lease with 120s TTL
-- 3. Heartbeat renews lease to now+120s (changed from 5 minutes)
-- 4. Lease scanner only checks phase=WORKER_DAG_RUNNING (not all phases)
-- 5. completeWorkerPhase clears lease_owner and lease_expires_at
-- 6. requeueOrphaned increments attemptNo and fails after 3 attempts

-- No schema changes needed; this migration documents the behavioral change.
