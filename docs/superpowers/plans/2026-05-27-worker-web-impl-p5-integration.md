# P5 — Integration, Docs & Acceptance

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (mostly checklist work, no fresh subagents needed).

**Goal:** Bring the whole stack up via docker-compose, manually walk the two happy paths from spec §10, update todo-final.md and ai-worker-web/SPEC.md, attach screenshots, and run the full CI gate locally.

**Working dir:** repo root

**Pre-flight:** P1 / P2 / P3 / P4 all complete; all branches merged into the integration branch.

---

### Task 1: Bring up local stack

- [ ] **Step 1: Env file**

```bash
cp .env.example .env  # if not already present
```

Ensure these are set (use `.env.example` defaults unless overridden):
- `DASHSCOPE_API_KEY=...` (cloud key)
- `DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`
- `AI_WORKER_JAVA_API_BASE_URL=http://meeting-api:8080`
- `AI_WORKER_INTERNAL_API_HMAC_SECRET=...`
- `AI_WORKER_CALLBACK_HMAC_SECRET=...`

- [ ] **Step 2: Bring up infra (Postgres + RabbitMQ + MinIO + Vault-dev)**

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
```

Wait until `docker ps` shows the four containers `running`.

- [ ] **Step 3: Apply Java migrations**

```bash
cd apps/meeting-api
./mvnw -pl meeting-api-start -am install -DskipTests
java -jar meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar &
sleep 10
curl -fsS http://localhost:8080/actuator/health
```

Expected: `{"status":"UP"}`. Migrations including `V202605270001__person_displayname_index.sql` applied.

- [ ] **Step 4: Start ai-worker (with admin UI)**

```bash
cd ../ai-worker
AI_WORKER_ENABLE_ADMIN=true uv run ai-worker-api &
sleep 5
curl -fsS http://localhost:8090/admin/healthz || curl -fsS http://localhost:8090/healthz
```

Expected: 200 OK.

- [ ] **Step 5: Start ai-worker-web dev**

```bash
cd ../ai-worker-web
npm run dev
```

Open `http://localhost:5173/` in browser; confirm login redirects to Java.

---

### Task 2: Walkthrough — enrollment new person (spec §10.1)

- [ ] **Step 1:** Login as a tenant admin user.
- [ ] **Step 2:** Navigate `/enrollment`.
- [ ] **Step 3:** Search "李四"; results empty.
- [ ] **Step 4:** Click "+ 新建人员"; fill `displayName=李四`; submit; modal closes; "李四" selected.
- [ ] **Step 5:** Click "创建录入会话"; session id appears.
- [ ] **Step 6:** Drop a 5-second wav; click "上传并预览"; quality ≥ 0.5.
- [ ] **Step 7:** Click "确认录入"; session state becomes `COMMITTED`.
- [ ] **Step 8:** Verify in Java DB:

```bash
psql $POSTGRES_URL -c "SELECT id, display_name FROM persons WHERE display_name='李四' ORDER BY created_at DESC LIMIT 1;"
psql $POSTGRES_URL -c "SELECT id, person_id FROM speaker_profiles WHERE person_id=(SELECT id FROM persons WHERE display_name='李四' ORDER BY created_at DESC LIMIT 1);"
psql $POSTGRES_URL -c "SELECT id, speaker_profile_id FROM speaker_enrollments ORDER BY created_at DESC LIMIT 1;"
```

Expected: one row each.

- [ ] **Step 9:** Screenshot:
  - `docs/superpowers/specs/screenshots/2026-05-27-enrollment-new-person.png`

---

### Task 3: Walkthrough — new meeting one-shot (spec §10.2)

- [ ] **Step 1:** Navigate `/meetings/new`.
- [ ] **Step 2:** Fill `title=季度评审`; security `INTERNAL`; add terms `["LLM", "DAG"]`.
- [ ] **Step 3:** Drop a sample PDF; wait for progress bar to complete; verify "已上传新文档：1".
- [ ] **Step 4:** Drop an MP3 ≤ 50MB.
- [ ] **Step 5:** Click "开始处理"; URL navigates to `/meetings/<new-id>`.
- [ ] **Step 6:** Watch SSE step grid — each step transitions `pending → running → succeeded`.
- [ ] **Step 7:** After `SUMMARY` and `EXTRACTION` reach `succeeded`, page shows nicely-rendered minutes markdown.
- [ ] **Step 8:** Speakers list shows confirmed names with "（自动认定）" if any confidence ≥ 0.85.
- [ ] **Step 9:** Click "创建导出"; wait for `SUCCEEDED`; click 下载 link; verify `.docx` opens in Word/LibreOffice.
- [ ] **Step 10:** Inspect Java audit log for `auto_confirm` entries:

```bash
docker logs <meeting-api-container> 2>&1 | grep auto_confirm | head
```

Expected: at least one `auto_confirm tenant=... task=... meeting=... label=SPEAKER_00 person=... score=0.X` line.

- [ ] **Step 11:** Screenshot:
  - `docs/superpowers/specs/screenshots/2026-05-27-new-meeting-pipeline.png`
  - `docs/superpowers/specs/screenshots/2026-05-27-new-meeting-result.png`

---

### Task 4: Failure-path smoke

- [ ] **Step 1: Duplicate name** — create another "李四" without forceCreate → modal shows duplicate list with "使用已有" + "仍创建新的" buttons.

- [ ] **Step 2: 415 file type** — try drag a `.exe`; UI shows `FILE_MIME_NOT_ALLOWED` from upstream; file not added to pendingDocs.

- [ ] **Step 3: CONFIDENTIAL meeting** — repeat Task 3 but pick `CONFIDENTIAL`; pipeline should stop at SUMMARY with `SECURITY_LEVEL_BLOCKED`; UI shows warning banner; docx export still possible.

---

### Task 5: Update todo-final.md

**Files:**
- Modify: `todo-final.md`

- [ ] **Step 1: Append section §9**

```markdown
---

## 9. 新人声纹 + 一路跑到底（2026-05-27 brainstorm）

设计：`docs/superpowers/specs/2026-05-27-worker-web-speaker-upload-design.md`
计划：`docs/superpowers/plans/2026-05-27-worker-web-impl-index.md`

### 已落地
- [ ] P1 契约（POST /api/persons + POST /api/files + 4 个 error codes）
- [ ] P2 Java（PersonController + FileUploadController + SpeakerAutoConfirmService + WorkerPhaseCompletedListener 改）
- [ ] P3 BFF（/admin/persons + /admin/files + enrollment 路径修复 + 删 start/finalize 透传）
- [ ] P4 前端（删 wizard + EnrollmentPage 加 modal + NewMeetingPage + MeetingDetailPage + MultipartUploader）
- [ ] P5 联调验收（两条 happy path 截图归档 + 全链路通）

完成后将 todo 项打勾并归档。
```

- [ ] **Step 2: Commit**

```bash
git add todo-final.md
git commit -m "docs: track new-person + one-shot pipeline delivery"
```

---

### Task 6: Update ai-worker-web SPEC.md

**Files:**
- Modify: `apps/ai-worker-web/SPEC.md` (if it exists; otherwise skip)

- [ ] **Step 1: Append change log entry**

Append (or update) at the bottom:

```markdown
## 2026-05-27 — Replace wizard with 3 pages

Single-form NewMeetingPage drives one-shot pipeline; MeetingDetailPage tails SSE; EnrollmentPage gets PersonCreateModal. Java side adds POST /api/persons, generic POST /api/files, SpeakerAutoConfirmService. See `docs/superpowers/specs/2026-05-27-worker-web-speaker-upload-design.md`.
```

- [ ] **Step 2: Commit**

```bash
git add apps/ai-worker-web/SPEC.md
git commit -m "docs(ai-worker-web): note 2026-05-27 page restructure"
```

---

### Task 7: Run full CI matrix locally

- [ ] **Step 1: Contracts**

```bash
cd packages/meeting-contracts && npm run check
```

Expected: PASS.

- [ ] **Step 2: Java**

```bash
cd ../../apps/meeting-api && ./mvnw verify -q
```

Expected: PASS.

- [ ] **Step 3: ai-worker**

```bash
cd ../ai-worker && uv run pyright ai_worker/ && uv run pytest tests/ -x -q
```

Expected: 0 errors, all green.

- [ ] **Step 4: Frontend (meeting-web — sanity check we didn't break it)**

```bash
cd ../meeting-web && npx tsc --noEmit && npm test
```

Expected: PASS.

- [ ] **Step 5: ai-worker-web**

```bash
cd ../ai-worker-web && npx tsc --noEmit && npm test && npm run build && npm run e2e
```

Expected: PASS, gzip < 200KB.

- [ ] **Step 6: DDL drift**

```bash
docker run --rm -e POSTGRES_PASSWORD=test -p 55432:5432 -d --name pg-final pgvector/pgvector:pg15
sleep 4
for sql in apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V*.sql; do
  psql -v ON_ERROR_STOP=1 postgresql://postgres:test@localhost:55432/postgres -f "$sql"
done
docker rm -f pg-final
```

Expected: every migration applies cleanly.

---

### Task 8: PR (optional — user decides timing)

- [ ] **Step 1: Compare branch to master**

```bash
git log --oneline master..HEAD
```

Confirm all expected commits present.

- [ ] **Step 2: Push branch and open PR** (only if user requests this step)

```bash
git push -u origin feature/worker-web-speaker-upload
gh pr create --title "feat: ai-worker-web new-person enrollment + one-shot pipeline" --body "$(cat <<'EOF'
## Summary
- Adds POST /api/persons + generic POST /api/files to Java
- SpeakerAutoConfirmService runs before LLM phase, threshold 0.85
- ai-worker BFF gains /admin/persons + /admin/files; enrollment commit aligned with Java
- ai-worker-web wizard removed; 3 new pages: EnrollmentPage (modal) / NewMeetingPage / MeetingDetailPage
- MultipartUploader with retry + abort + progress

## Test plan
- [x] CI matrix locally green (5 jobs)
- [x] Spec §10.1 happy path manually walked
- [x] Spec §10.2 happy path manually walked
- [x] Duplicate name + 415 MIME + CONFIDENTIAL fail-closed all verified
EOF
)"
```

**P5 complete. Feature complete.**
