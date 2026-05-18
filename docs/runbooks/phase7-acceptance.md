# Phase 7 Acceptance Runbook

> 6-check pre-release verification of the compliance subsystem
> (legal hold, deletion job, break-glass, audit) — final-check.md E2
> and 678-plan.md 7.8.

Run order matters: 7.8.1–7.8.3 depend on a clean meeting under hold,
7.8.4 needs a fresh break-glass request, 7.8.5 needs at least one prior
admin-only write event in the audit log. Stop at the first failure.

## Pre-conditions

- Full-stack up: `docker compose --profile full-stack up -d`
- Demo user (`demo@meeting.local` / `demo`) and admin user
  (`admin@meeting.local` / `admin`) seeded by Flyway baseline.
- All migrations applied (`./mvnw -pl meeting-api-start verify` already
  exercises Flyway against a Testcontainer).

## 7.8.1 — Place hold → DELETE 423 → release → 200

Automated by `infra/meeting-infra/scripts/legal-hold-lifecycle-smoke.sh`.

```bash
bash infra/meeting-infra/scripts/legal-hold-lifecycle-smoke.sh
```

Manual variant (when investigating a single step):

1. `POST /api/meetings` as user → record `meetingId`.
2. `POST /admin/legal-holds` as admin with `scopeType=MEETING, scopeId=<id>`.
3. `DELETE /api/meetings/<id>` as user → expect `423 LEGAL_HOLD_BLOCKED`.
4. `POST /admin/legal-holds/<holdId>/release` as admin.
5. `DELETE /api/meetings/<id>` as user → expect `200`, `status=DELETED`.

## 7.8.2 — Deletion job end-to-end with certificate

1. `POST /admin/deletion-jobs` as admin with
   `{"scopeType":"MEETING","scopeId":"<m_…>","reason":"test"}`.
2. Poll `GET /admin/deletion-jobs/<id>` until `status` reaches
   `SUCCEEDED` or `PARTIAL_FAILED` (no transient `RUNNING`).
3. `GET /admin/deletion-jobs/<id>/certificate` — verify:
   - `certificateHash` is 64 hex chars.
   - `objectHashes[]` lists every MinIO bucket key removed, each with a
     `sha256` of the deleted object's bytes captured pre-delete.
   - `deletedRows` and `deletedFiles` non-zero for at least one
     subsystem (rows in PG, files in MinIO).
4. Re-hashing the certificate JSON minus the `certificateHash` field with
   SHA-256 must reproduce the persisted hash.

## 7.8.3 — Deletion + late hold race ⇒ BLOCKED_BY_LEGAL_HOLD

1. Pause the runner before claim: set
   `meeting.deletion.runner.enabled=false`, restart meeting-api.
2. `POST /admin/deletion-jobs` for a fresh meeting → job rows = REQUESTED.
3. `POST /admin/legal-holds` for the same scope/id.
4. Re-enable runner and let it process the queue.
5. Expect job status = `BLOCKED_BY_LEGAL_HOLD`, `errorCode` set, no
   destructive writes executed (verify the MinIO objects still exist).
6. Audit row `DELETION_EXECUTE` with `result=BLOCKED` must be present.

## 7.8.4 — Break-glass approval → access → expiry

1. As user: `POST /admin/break-glass`
   `{"scopeType":"MEETING","scopeId":"<id>","reason":"audit_review"}`.
2. As admin: `POST /admin/break-glass/<id>/approve` with
   `validUntil` 60 s in the future (the default 4-hour window is too
   long for an acceptance run; override via API or `validForMinutes:1`).
3. As user without normal permission on the meeting: `GET .../<id>`
   inside the window → expect 200 + audit row
   `BREAK_GLASS_ACCESS`.
4. Wait until window expires (or run the expiry scanner manually:
   `POST /admin/break-glass/scan` if exposed).
5. Same `GET` after expiry → expect 403 `PERMISSION_DENIED`.

## 7.8.5 — Audit query: admin sees everything; user is RLS-scoped

1. As admin: `GET /admin/audit-events?from=<-90d>&to=now` — verify the
   `BREAK_GLASS_ACCESS` and `MEETING_DELETED` rows produced above are
   visible.
2. As user: same query (or `/api/audit-events` if a user-facing route
   exists) — expect to see only rows where `actorUserId = self`. RLS at
   the row level prevents cross-user reads even if the endpoint omits
   the filter.

## 7.8.6 — Audit query window cap

1. As admin: `GET /admin/audit-events?from=<-180d>&to=now` — expect
   `400 AUDIT_QUERY_TOO_BROAD`. The 90-day cap is enforced in the
   application service, not just at the controller.

## Closing the loop

If all six checks pass, file the acceptance result in `todo.md` under
"阶段 7 收尾完成（YYYY-MM-DD）" with a one-line per check + the date of
the run. If any check fails, fix-then-rerun the whole sequence — a
partial pass is not acceptance.
