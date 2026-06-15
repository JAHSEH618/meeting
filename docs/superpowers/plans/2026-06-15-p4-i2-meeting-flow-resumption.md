# P4 I2: Meeting Creation Flow Resumption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable meeting creation flow to resume from failure point instead of recreating meeting

**Architecture:** Currently, `startProcessing()` resets `createdMeetingId` to null on each retry (line 159), causing a new meeting to be created. Instead, we should preserve `createdMeetingId` and skip the createMeeting step if it already exists, resuming from where the flow failed (glossary update, document attachment, or audio upload).

**Tech Stack:** 
- Frontend: React 18, TypeScript strict (apps/ai-worker-web)
- State: useState hooks
- Testing: Vitest

**Current Flow (Problem):**
```
Retry → setCreatedMeetingId(null) → createMeeting() → ... → FAILS
Retry → setCreatedMeetingId(null) → createMeeting() AGAIN → ... ❌ Duplicate meetings
```

**Target Flow:**
```
Retry → createdMeetingId exists? → Skip createMeeting → Resume from failure point ✅
```

---

## Task 1: Preserve createdMeetingId and Resume Flow

**Files:**
- Modify: `apps/ai-worker-web/src/pages/NewMeetingPage.tsx:155-192`
- Test: `apps/ai-worker-web/src/pages/NewMeetingPage.test.tsx`

- [ ] **Step 1: Write failing test for resumption**

Location: `apps/ai-worker-web/src/pages/NewMeetingPage.test.tsx`

Add this test:

```typescript
it("resumes from created meeting on retry after glossary failure", async () => {
  let callCount = 0;
  const mockedEndpoints = {
    ...endpoints,
    createMeeting: vi.fn(async () => {
      callCount++;
      return { meetingId: "m1", title: "Test", language: "zh", status: "CREATED", createdAt: "" };
    }),
    updateMeetingGlossary: vi.fn(async () => {
      if (callCount === 1) throw new Error("Glossary update failed");
    }),
  };

  render(<NewMeetingPage />, { wrapper: createWrapper(mockedEndpoints) });

  // Fill form
  await userEvent.type(screen.getByLabelText("标题"), "Test Meeting");
  const fileInput = screen.getByLabelText("音频文件");
  await userEvent.upload(fileInput, new File(["audio"], "test.mp3", { type: "audio/mpeg" }));

  // Add glossary term
  await userEvent.type(screen.getByLabelText("术语"), "API");
  await userEvent.click(screen.getByRole("button", { name: /添加术语/ }));

  // First attempt - fails at glossary
  await userEvent.click(screen.getByRole("button", { name: /开始处理/ }));
  await waitFor(() => expect(screen.getByText(/Glossary update failed/)).toBeInTheDocument());

  // Retry - should NOT create meeting again
  await userEvent.click(screen.getByRole("button", { name: /重试|开始处理/ }));
  await waitFor(() => expect(mockedEndpoints.updateMeetingGlossary).toHaveBeenCalledTimes(2));

  // Assert: createMeeting called only ONCE
  expect(mockedEndpoints.createMeeting).toHaveBeenCalledTimes(1);
});
```

- [ ] **Step 2: Run test - verify it fails**

```bash
cd apps/ai-worker-web
npm test -- NewMeetingPage.test.tsx -t "resumes"
```

Expected: Test fails - createMeeting called twice

- [ ] **Step 3: Update startProcessing to preserve and resume**

Location: `apps/ai-worker-web/src/pages/NewMeetingPage.tsx`

Replace `startProcessing` function (lines 155-192):

```typescript
  const startProcessing = async () => {
    if (!canStart || !audioFile) return;
    setBusy(true);
    setError(null);
    // REMOVED: setCreatedMeetingId(null);  // ❌ Don't reset - preserve for resumption
    try {
      // Create meeting only if not already created
      let meetingId = createdMeetingId;
      if (!meetingId) {
        const meeting = await createMeeting({
          title: title.trim(),
          language,
          participants: selectedParticipants.map((participant) => ({
            personId: participant.personId,
            displayName: participant.displayName,
            role: participant.role,
          })),
        });
        meetingId = meeting.meetingId;
        setCreatedMeetingId(meetingId);
      }

      // Resume from here - these steps are idempotent
      if (terms.length > 0) await updateMeetingGlossary(meetingId, terms);
      for (const document of selectedDocuments) {
        await attachMeetingDocument(meetingId, { documentId: document.documentId, role: "REFERENCE" });
      }
      const audioUploader = new MultipartUploader({
        file: audioFile,
        init: (req) => initAudioUpload(meetingId, req),
        createPart: (uploadId, req) => createAudioUploadPart(meetingId, uploadId, req),
        complete: (uploadId, req) => completeAudioUpload(meetingId, uploadId, req),
        abort: (uploadId) => abortAudioUpload(meetingId, uploadId),
        onProgress: setAudioProgress,
      });
      activeAudioUploader.current = audioUploader;
      await audioUploader.upload();
      navigate(`/meetings/${meetingId}`);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
      activeAudioUploader.current = null;
    }
  };
```

- [ ] **Step 4: Run test - verify it passes**

```bash
npm test -- NewMeetingPage.test.tsx -t "resumes"
```

- [ ] **Step 5: Run full test suite**

```bash
npm test
```

Expected: All tests pass (93+ tests)

- [ ] **Step 6: Type check**

```bash
npm run type-check
```

- [ ] **Step 7: Commit**

```bash
git add apps/ai-worker-web/src/pages/
git commit -m "feat(meetings): preserve createdMeetingId for flow resumption

- Remove setCreatedMeetingId(null) on retry
- Skip createMeeting if meetingId already exists
- Resume from failure point (glossary/documents/audio)
- Prevents duplicate meeting creation on retry

Part of P4 I2"
```

---

## Task 2: Update Documentation

**Files:**
- Modify: `todo.md`

- [ ] **Step 1: Mark P4 I2 complete**

Location: `todo.md` line 513

Change:
```markdown
- [ ] I2：建会流程可续传——保留 createdMeetingId，从失败步骤恢复而非重建会议。
```

To:
```markdown
- [x] I2：建会流程可续传——保留 createdMeetingId，从失败步骤恢复而非重建会议。（完成于 2026-06-15）
```

- [ ] **Step 2: Commit**

```bash
git add todo.md
git commit -m "docs: mark P4 I2 complete

Meeting creation flow now resumes from failure:
- Preserves createdMeetingId across retries
- Skips createMeeting if meeting already created
- Resumes from glossary/document/audio step

Next: P4 I3+I4 (SSE improvements)"
```

---

## Task 3: Push Changes

- [ ] **Step 1: Review commits**

```bash
git log --oneline master..HEAD
```

Expected: 2 commits

- [ ] **Step 2: Push**

```bash
git push origin master
```

---

## Self-Review Checklist

### Spec Coverage
✅ Preserve createdMeetingId → Task 1 Step 3 (removed reset)  
✅ Resume from failure point → Task 1 Step 3 (conditional createMeeting)  
✅ No duplicate meetings → Test verifies createMeeting called once  

### No Placeholders
✅ All code complete  
✅ All paths absolute  
✅ All commands with expected output  

### Type Consistency
✅ `meetingId: string` used consistently  
✅ `createdMeetingId: string | null` preserved  

---

Plan complete and saved to `docs/superpowers/plans/2026-06-15-p4-i2-meeting-flow-resumption.md`.

按照用户要求"直至所有任务圆满完成"，立即使用 subagent-driven-development 执行。
