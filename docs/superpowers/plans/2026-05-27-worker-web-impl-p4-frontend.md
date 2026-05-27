# P4 — ai-worker-web Frontend Implementation

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Replace wizard with 3 independent pages, add MultipartUploader + PersonCreateModal, wire to new BFF endpoints, rewrite tests.

**Working dir:** `apps/ai-worker-web/`

**Pre-flight:** P1 codegen merged (`src/shared/api/types.gen.ts` has new types). `npm install` done.

**Run gates:** `npx tsc --noEmit && npm test && npm run build` and `npm run e2e`.

---

### Task 1: New api endpoints + types

**Files:**
- Modify: `apps/ai-worker-web/src/shared/api/types.ts`
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts`

- [ ] **Step 1: Add types**

Append to `types.ts`:

```ts
// Persons
export interface PersonDTO {
  personId: string;
  displayName: string;
  email: string | null;
  externalId: string | null;
  createdAt: string;
}
export interface CreatePersonRequest {
  displayName: string;
  email?: string;
  externalId?: string;
  forceCreate?: boolean;
}
export interface PersonDuplicateError {
  code: "PERSON_DUPLICATE";
  message: string;
  retryable: false;
  details: { matches: PersonDTO[] };
}

// Generic file upload
export interface FileUploadPartDTO {
  partNumber: number;
  presignedUrl: string;
  expiresAt: string;
}
export interface FileUploadSessionDTO {
  uploadId: string;
  parts: FileUploadPartDTO[];
}
export interface FileUploadCompleteResponseDTO {
  fileId: string;
  sha256: string;
  sizeBytes: number;
  contentType: string;
}

// Audio multipart helper (mirror Java AudioUploadController DTOs)
export interface AudioUploadSessionDTO {
  uploadId: string;
  parts: { partNumber: number; presignedUrl: string; expiresAt: string }[];
}
```

- [ ] **Step 2: Add endpoint helpers**

In `endpoints.ts`, add:

```ts
import { apiCall } from "./client";
import type {
  PersonDTO,
  CreatePersonRequest,
  FileUploadSessionDTO,
  FileUploadCompleteResponseDTO,
  AudioUploadSessionDTO,
} from "./types";

export async function createPerson(req: CreatePersonRequest): Promise<PersonDTO> {
  return apiCall<PersonDTO>("/admin/persons", { method: "POST", body: JSON.stringify(req) });
}
export async function searchPersons(q: string, opts?: { signal?: AbortSignal }): Promise<PersonDTO[]> {
  return apiCall<PersonDTO[]>(`/admin/persons?q=${encodeURIComponent(q)}`, { method: "GET", signal: opts?.signal });
}

// Generic file upload (PDF/docx/pptx/txt/md)
export async function initFileUpload(req: {
  fileName: string; contentType: string; fileSizeBytes: number; fileSha256: string; partSizeBytes?: number;
}): Promise<FileUploadSessionDTO> {
  return apiCall<FileUploadSessionDTO>("/admin/files/uploads", { method: "POST", body: JSON.stringify(req) });
}
export async function completeFileUpload(uploadId: string, req: {
  fileSha256: string; parts: { partNumber: number; partSha256: string; etag: string }[];
}): Promise<FileUploadCompleteResponseDTO> {
  return apiCall<FileUploadCompleteResponseDTO>(`/admin/files/uploads/${uploadId}/complete`, { method: "POST", body: JSON.stringify(req) });
}
export async function abortFileUpload(uploadId: string): Promise<void> {
  await apiCall<void>(`/admin/files/uploads/${uploadId}/abort`, { method: "POST" });
}

// Audio upload (meeting-scoped, multipart)
export async function initAudioUpload(meetingId: string, req: {
  fileName: string; contentType: string; fileSizeBytes: number; fileSha256: string; partSizeBytes?: number;
}): Promise<AudioUploadSessionDTO> {
  return apiCall<AudioUploadSessionDTO>(
    `/admin/meetings/${meetingId}/files/audio/uploads`,
    { method: "POST", body: JSON.stringify(req) },
  );
}
export async function completeAudioUpload(meetingId: string, uploadId: string, req: {
  fileSha256: string; durationMs?: number; parts: { partNumber: number; partSha256: string; etag: string }[];
}): Promise<{ uploadId: string; status: string }> {
  return apiCall(`/admin/meetings/${meetingId}/files/audio/uploads/${uploadId}/complete`, {
    method: "POST", body: JSON.stringify(req),
  });
}
```

(If `meetings.py` BFF doesn't already proxy `/api/meetings/{id}/files/audio/uploads`, add proxy routes there as part of P3 Task 5. Audit P3 before this step.)

**Remove** old exports `startMeetingProcessing` and `finalizeMeeting` from `endpoints.ts` and update any imports that broke (the deleted pages will be removed in Task 6 — keep compile passing until then by deleting the import lines transitively).

- [ ] **Step 3: tsc passes**

```bash
cd apps/ai-worker-web
npx tsc --noEmit
```

Expected: 0 errors.

- [ ] **Step 4: Commit**

```bash
git add apps/ai-worker-web/src/shared/api/types.ts apps/ai-worker-web/src/shared/api/endpoints.ts
git commit -m "feat(ai-worker-web): add person + file upload + audio upload endpoint helpers"
```

---

### Task 2: MultipartUploader helper (TDD)

**Files:**
- Create: `apps/ai-worker-web/src/shared/upload/MultipartUploader.ts`
- Create: `apps/ai-worker-web/src/shared/upload/MultipartUploader.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { MultipartUploader } from "./MultipartUploader";

const PART = 5 * 1024 * 1024; // 5 MB

function file(size: number, type = "application/pdf"): File {
  const buf = new Uint8Array(size);
  return new File([buf], "ref.pdf", { type });
}

describe("MultipartUploader", () => {
  beforeEach(() => {
    globalThis.fetch = vi.fn(async (url: any, init: any) => {
      const u = String(url);
      if (u.startsWith("https://presign/")) {
        return { ok: true, status: 200, headers: new Headers({ etag: '"e1"' }) } as any;
      }
      return { ok: true, status: 200, json: async () => ({}) } as any;
    });
  });

  it("uploads small file in one part", async () => {
    const init = vi.fn(async () => ({ uploadId: "u1", parts: [{ partNumber: 1, presignedUrl: "https://presign/1", expiresAt: "" }] }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const u = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, complete, abort: vi.fn() });
    const r = await u.upload();
    expect(r.fileId).toBe("f1");
    expect(init).toHaveBeenCalledTimes(1);
    expect(complete).toHaveBeenCalledTimes(1);
  });

  it("emits onProgress with completion fractions", async () => {
    const events: number[] = [];
    const init = vi.fn(async () => ({ uploadId: "u1", parts: [{ partNumber: 1, presignedUrl: "https://presign/1", expiresAt: "" }] }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const u = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, complete, abort: vi.fn(), onProgress: (p) => events.push(p) });
    await u.upload();
    expect(events[events.length - 1]).toBe(1);
  });

  it("retries a failing part 3 times before giving up", async () => {
    let attempts = 0;
    globalThis.fetch = vi.fn(async () => {
      attempts++;
      if (attempts < 3) return { ok: false, status: 500, headers: new Headers() } as any;
      return { ok: true, status: 200, headers: new Headers({ etag: '"ok"' }) } as any;
    });
    const init = vi.fn(async () => ({ uploadId: "u1", parts: [{ partNumber: 1, presignedUrl: "https://presign/1", expiresAt: "" }] }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const u = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, complete, abort: vi.fn() });
    await u.upload();
    expect(attempts).toBe(3);
  });

  it("abort cancels in-flight upload", async () => {
    const init = vi.fn(async () => ({ uploadId: "u1", parts: [{ partNumber: 1, presignedUrl: "https://presign/1", expiresAt: "" }] }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const abort = vi.fn();
    const u = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, complete, abort });
    const p = u.upload();
    u.abort();
    await expect(p).rejects.toThrow(/aborted/i);
    expect(abort).toHaveBeenCalledWith("u1");
  });
});
```

- [ ] **Step 2: Run failing test**

```bash
npx vitest run src/shared/upload/MultipartUploader.test.ts
```

Expected: FAIL.

- [ ] **Step 3: Implement**

```ts
// src/shared/upload/MultipartUploader.ts
type InitFn = (req: {
  fileName: string; contentType: string; fileSizeBytes: number; fileSha256: string; partSizeBytes: number;
}) => Promise<{ uploadId: string; parts: { partNumber: number; presignedUrl: string; expiresAt: string }[] }>;
type CompleteFn = (uploadId: string, req: {
  fileSha256: string; parts: { partNumber: number; partSha256: string; etag: string }[];
}) => Promise<{ fileId: string; sha256: string; sizeBytes: number; contentType: string }>;
type AbortFn = (uploadId: string) => Promise<void>;

export interface MultipartUploaderOpts {
  file: File;
  partSizeBytes?: number;
  init: InitFn;
  complete: CompleteFn;
  abort: AbortFn;
  onProgress?: (fraction: number) => void;
  maxRetries?: number;
}

export class MultipartUploader {
  private aborted = false;
  private uploadId: string | null = null;
  constructor(private readonly opts: MultipartUploaderOpts) {}

  abort(): void {
    this.aborted = true;
    if (this.uploadId) void this.opts.abort(this.uploadId).catch(() => {});
  }

  async upload(): Promise<{ fileId: string; sha256: string; sizeBytes: number; contentType: string }> {
    const part = this.opts.partSizeBytes ?? 5 * 1024 * 1024;
    const fullSha = await sha256(await this.opts.file.arrayBuffer());
    const session = await this.opts.init({
      fileName: this.opts.file.name,
      contentType: this.opts.file.type || "application/octet-stream",
      fileSizeBytes: this.opts.file.size,
      fileSha256: fullSha,
      partSizeBytes: part,
    });
    this.uploadId = session.uploadId;

    const completed: { partNumber: number; partSha256: string; etag: string }[] = [];
    let uploaded = 0;
    for (let i = 0; i < session.parts.length; i++) {
      if (this.aborted) throw new Error("upload aborted");
      const p = session.parts[i];
      const start = i * part;
      const slice = this.opts.file.slice(start, Math.min(start + part, this.opts.file.size));
      const partSha = await sha256(await slice.arrayBuffer());
      const etag = await this.putWithRetry(p.presignedUrl, slice, this.opts.maxRetries ?? 3);
      completed.push({ partNumber: p.partNumber, partSha256: partSha, etag });
      uploaded += slice.size;
      this.opts.onProgress?.(uploaded / this.opts.file.size);
    }
    return await this.opts.complete(session.uploadId, { fileSha256: fullSha, parts: completed });
  }

  private async putWithRetry(url: string, blob: Blob, maxRetries: number): Promise<string> {
    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      if (this.aborted) throw new Error("upload aborted");
      const resp = await fetch(url, { method: "PUT", body: blob });
      if (resp.ok) {
        return (resp.headers.get("etag") ?? "").replace(/^"|"$/g, "");
      }
      if (attempt === maxRetries) throw new Error(`part upload failed after ${maxRetries} attempts (status ${resp.status})`);
      await new Promise((res) => setTimeout(res, 250 * 2 ** (attempt - 1)));
    }
    throw new Error("unreachable");
  }
}

async function sha256(buf: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
```

- [ ] **Step 4: Test passes**

```bash
npx vitest run src/shared/upload/MultipartUploader.test.ts
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/ai-worker-web/src/shared/upload/
git commit -m "feat(ai-worker-web): MultipartUploader with retry + abort + progress"
```

---

### Task 3: PersonCreateModal component (TDD)

**Files:**
- Create: `apps/ai-worker-web/src/shared/components/PersonCreateModal.tsx`
- Create: `apps/ai-worker-web/src/shared/components/PersonCreateModal.test.tsx`

- [ ] **Step 1: Failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { PersonCreateModal } from "./PersonCreateModal";

describe("PersonCreateModal", () => {
  it("submits displayName and calls onCreated", async () => {
    const onCreated = vi.fn();
    const onClose = vi.fn();
    const createFn = vi.fn(async () => ({ personId: "p1", displayName: "李四" }));
    render(<PersonCreateModal open onClose={onClose} onCreated={onCreated} createFn={createFn as any} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.click(screen.getByRole("button", { name: /创建/ }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ personId: "p1", displayName: "李四" }));
  });

  it("shows duplicate matches and offers force-create", async () => {
    const onCreated = vi.fn();
    const onClose = vi.fn();
    const matches = [{ personId: "p1", displayName: "李四", email: "a@x.com", externalId: null, createdAt: "" }];
    let first = true;
    const createFn = vi.fn(async (req: any) => {
      if (first) {
        first = false;
        const e: any = new Error("PERSON_DUPLICATE");
        e.code = "PERSON_DUPLICATE";
        e.details = { matches };
        throw e;
      }
      return { personId: "p2", displayName: req.displayName };
    });
    render(<PersonCreateModal open onClose={onClose} onCreated={onCreated} createFn={createFn as any} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.click(screen.getByRole("button", { name: /创建/ }));
    await waitFor(() => expect(screen.getByText(/已存在/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /使用已有/ }));
    expect(onCreated).toHaveBeenCalledWith(matches[0]);
  });
});
```

- [ ] **Step 2: Fail**

```bash
npx vitest run src/shared/components/PersonCreateModal.test.tsx
```

Expected: FAIL.

- [ ] **Step 3: Implement**

```tsx
import { useState } from "react";
import type { PersonDTO, CreatePersonRequest } from "@/shared/api/types";
import { ApiError } from "@/shared/api/client";

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: (p: PersonDTO) => void;
  createFn?: (req: CreatePersonRequest) => Promise<PersonDTO>;
}

export function PersonCreateModal({ open, onClose, onCreated, createFn }: Props) {
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [duplicates, setDuplicates] = useState<PersonDTO[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const submit = async (force: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const fn = createFn ?? (await import("@/shared/api/endpoints")).createPerson;
      const created = await fn({ displayName: displayName.trim(), email: email.trim() || undefined, forceCreate: force });
      onCreated(created);
    } catch (e) {
      if ((e as any).code === "PERSON_DUPLICATE" || (e instanceof ApiError && (e as ApiError).error.code === "PERSON_DUPLICATE")) {
        const matches = ((e as any).details ?? (e as any).error?.details)?.matches ?? [];
        setDuplicates(matches);
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div role="dialog" aria-modal="true" className="modal">
      <div className="modal__panel card stack">
        <h2>新建人员</h2>
        <div className="field">
          <label className="field__label" htmlFor="pc-name">姓名</label>
          <input id="pc-name" className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        <div className="field">
          <label className="field__label" htmlFor="pc-email">邮箱（可选）</label>
          <input id="pc-email" className="input" value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        {duplicates && duplicates.length > 0 && (
          <div className="card stack" data-testid="duplicates">
            <strong>已存在 {duplicates.length} 个同名人员</strong>
            <ul>
              {duplicates.map((m) => (
                <li key={m.personId}>
                  <button className="button button--secondary" onClick={() => onCreated(m)}>
                    使用已有 {m.displayName}{m.email ? ` (${m.email})` : ""}
                  </button>
                </li>
              ))}
            </ul>
            <button className="button" disabled={busy} onClick={() => void submit(true)}>仍创建新的</button>
          </div>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <div className="row">
          <button className="button" onClick={onClose}>取消</button>
          <button
            className="button button--primary"
            disabled={busy || !displayName.trim() || !!duplicates}
            onClick={() => void submit(false)}
          >
            创建
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Test passes**

```bash
npx vitest run src/shared/components/PersonCreateModal.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/ai-worker-web/src/shared/components/PersonCreateModal*
git commit -m "feat(ai-worker-web): PersonCreateModal with duplicate handling"
```

---

### Task 4: EnrollmentPage — integrate modal

**Files:**
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.tsx`

- [ ] **Step 1: Wire button + modal**

After the search input/results in `EnrollmentPage.tsx`, insert:

```tsx
import { PersonCreateModal } from "@/shared/components/PersonCreateModal";
// ... inside component, with other useStates:
const [modalOpen, setModalOpen] = useState(false);

// after the persons.length list, before step 2 section:
<button className="button button--secondary" onClick={() => setModalOpen(true)}>+ 新建人员</button>
<PersonCreateModal
  open={modalOpen}
  onClose={() => setModalOpen(false)}
  onCreated={(p) => {
    setPersonId(p.id ?? (p as any).personId);
    setModalOpen(false);
  }}
/>
```

- [ ] **Step 2: Add page test**

Create `EnrollmentPage.test.tsx`:

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { EnrollmentPage } from "./EnrollmentPage";

describe("EnrollmentPage", () => {
  it("renders +新建人员 button", () => {
    render(<EnrollmentPage />);
    expect(screen.getByRole("button", { name: /新建人员/ })).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Tests pass**

```bash
npx vitest run src/pages/EnrollmentPage.test.tsx src/shared/components/PersonCreateModal.test.tsx
npx tsc --noEmit
```

Expected: PASS, 0 tsc errors.

- [ ] **Step 4: Commit**

```bash
git add apps/ai-worker-web/src/pages/EnrollmentPage.tsx apps/ai-worker-web/src/pages/EnrollmentPage.test.tsx
git commit -m "feat(ai-worker-web): EnrollmentPage + 新建人员 modal flow"
```

---

### Task 5: NewMeetingPage (no TDD on JSX — vitest covers logic)

**Files:**
- Create: `apps/ai-worker-web/src/pages/NewMeetingPage.tsx`
- Create: `apps/ai-worker-web/src/pages/NewMeetingPage.test.tsx`

- [ ] **Step 1: Page implementation**

```tsx
import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@/shared/api/client";
import {
  attachMeetingDocument,
  createDocument,
  createMeeting,
  searchDocuments,
  updateMeetingGlossary,
  initFileUpload,
  completeFileUpload,
  abortFileUpload,
  initAudioUpload,
  completeAudioUpload,
} from "@/shared/api/endpoints";
import type { DocumentSummaryDTO, GlossaryTermDTO } from "@/shared/api/types";
import { MultipartUploader } from "@/shared/upload/MultipartUploader";
import { useDebouncedSearch } from "@/shared/hooks/useDebouncedSearch";

type SecurityLevel = "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "SECRET";

interface PendingDoc { fileId: string; title: string; }

export function NewMeetingPage() {
  const navigate = useNavigate();
  const [title, setTitle] = useState("");
  const [securityLevel, setSecurityLevel] = useState<SecurityLevel>("INTERNAL");
  const [terms, setTerms] = useState<GlossaryTermDTO[]>([]);
  const [termDraft, setTermDraft] = useState("");
  const [audioFile, setAudioFile] = useState<File | null>(null);
  const [audioProgress, setAudioProgress] = useState(0);
  const [pendingDocs, setPendingDocs] = useState<PendingDoc[]>([]);
  const [existingDocIds, setExistingDocIds] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const docFetcher = useCallback((q: string, signal: AbortSignal) => searchDocuments(q, { signal }), []);
  const docSearch = useDebouncedSearch<DocumentSummaryDTO>(docFetcher);

  const addTerm = () => {
    const t = termDraft.trim();
    if (!t || t.length > 64 || terms.length >= 200) return;
    if (terms.some((x) => x.term.toLowerCase() === t.toLowerCase())) return;
    setTerms([...terms, { term: t, aliases: [] }]);
    setTermDraft("");
  };

  const handleDropDoc = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const uploader = new MultipartUploader({
        file,
        init: (req) => initFileUpload(req),
        complete: (uploadId, req) => completeFileUpload(uploadId, req),
        abort: (uploadId) => abortFileUpload(uploadId),
      });
      const completed = await uploader.upload();
      const doc = await createDocument({
        title: file.name,
        fileId: completed.fileId,
        documentType: deriveDocType(file.type),
        securityLevel,
        contentHash: completed.sha256,
      });
      setPendingDocs((prev) => [...prev, { fileId: completed.fileId, title: file.name }]);
      setExistingDocIds((prev) => [...prev, doc.documentId]);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
    }
  };

  const start = async () => {
    if (!title.trim() || !audioFile) {
      setError("请填写标题并选择音频文件");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const m = await createMeeting({ title, securityLevel, language: "zh", participants: [] });
      if (terms.length > 0) await updateMeetingGlossary(m.meetingId, terms);
      for (const docId of existingDocIds) {
        await attachMeetingDocument(m.meetingId, { documentId: docId, role: "REFERENCE" });
      }
      const audioUploader = new MultipartUploader({
        file: audioFile,
        init: (req) => initAudioUpload(m.meetingId, req),
        complete: (uploadId, req) => completeAudioUpload(m.meetingId, uploadId, req),
        abort: () => Promise.resolve(),
        onProgress: setAudioProgress,
      });
      await audioUploader.upload();
      // Java auto-creates ProcessingTask on audio complete (hold=false)
      navigate(`/meetings/${m.meetingId}`);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="stack">
      <header className="page-header"><h1 className="page-title">新建会议</h1></header>
      <section className="card stack">
        <div className="field">
          <label className="field__label" htmlFor="nm-title">标题</label>
          <input id="nm-title" className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="field">
          <label className="field__label" htmlFor="nm-sec">安全级别</label>
          <select id="nm-sec" className="select" value={securityLevel} onChange={(e) => setSecurityLevel(e.target.value as SecurityLevel)}>
            <option>PUBLIC</option><option>INTERNAL</option><option>CONFIDENTIAL</option><option>SECRET</option>
          </select>
        </div>
      </section>

      <section className="card stack" aria-labelledby="nm-terms-h">
        <h2 id="nm-terms-h">术语</h2>
        <div className="row">
          <input className="input" value={termDraft} maxLength={64}
            onChange={(e) => setTermDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addTerm(); } }}
            placeholder="按 Enter 添加" />
          <button className="button" onClick={addTerm} disabled={!termDraft.trim()}>+ 添加</button>
        </div>
        <div>
          {terms.map((t) => (
            <span key={t.term} className="chip">{t.term}
              <button type="button" className="chip__remove"
                onClick={() => setTerms(terms.filter((x) => x.term !== t.term))}>×</button>
            </span>
          ))}
        </div>
      </section>

      <section className="card stack" aria-labelledby="nm-docs-h">
        <h2 id="nm-docs-h">参考文档</h2>
        <input className="input" placeholder="搜索已有文档…" onChange={(e) => docSearch.search(e.target.value)} aria-label="document search" />
        {docSearch.results?.map((d) => (
          <div key={d.documentId}>
            {d.title}
            <button className="button button--secondary"
              disabled={existingDocIds.includes(d.documentId)}
              onClick={() => setExistingDocIds((p) => [...p, d.documentId])}>关联</button>
          </div>
        ))}
        <label htmlFor="nm-doc-drop" className="upload-dropzone">
          <input id="nm-doc-drop" type="file" accept=".pdf,.docx,.pptx,.txt,.md"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void handleDropDoc(f); }}
            className="upload-dropzone__input" />
          <span>+ 拖入或选择新参考文档</span>
        </label>
        <p className="page-subtitle">已上传新文档：{pendingDocs.length}；已选已有：{existingDocIds.length - pendingDocs.length}</p>
      </section>

      <section className="card stack" aria-labelledby="nm-audio-h">
        <h2 id="nm-audio-h">音频文件</h2>
        <label htmlFor="nm-audio" className="upload-dropzone">
          <input id="nm-audio" type="file" accept="audio/*"
            onChange={(e) => setAudioFile(e.target.files?.[0] ?? null)}
            className="upload-dropzone__input" />
          <span>{audioFile ? audioFile.name : "选择音频文件"}</span>
        </label>
        {audioProgress > 0 && <progress value={audioProgress} max={1} aria-label="audio upload progress" />}
      </section>

      {error && <div className="banner banner--danger" role="alert">{error}</div>}

      <button className="button button--primary" disabled={busy || !title.trim() || !audioFile}
        onClick={() => void start()} data-testid="start">
        {busy ? "处理中…" : "开始处理"}
      </button>
    </div>
  );
}

function deriveDocType(mime: string): string {
  switch (mime) {
    case "application/pdf": return "PDF";
    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "DOCX";
    case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "PPTX";
    case "text/plain": return "TXT";
    case "text/markdown": return "MD";
    default: return "OTHER";
  }
}

function formatError(e: unknown): string {
  if (e instanceof ApiError) return `${e.error.code}: ${e.error.message}`;
  return e instanceof Error ? e.message : String(e);
}
```

Add `createDocument` to `endpoints.ts` (always — Java already has `POST /api/documents`, this just routes via BFF):

```ts
// in endpoints.ts
export async function createDocument(req: {
  title: string; fileId: string; documentType: string; securityLevel: SecurityLevel; contentHash: string;
}): Promise<{ documentId: string }> {
  return apiCall<{ documentId: string }>("/admin/documents", { method: "POST", body: JSON.stringify(req) });
}
```

Ensure `/admin/documents` exists in the BFF — if `meetings.py` doesn't expose it, add a tiny passthrough handler in P3 Task 5 before merging.

- [ ] **Step 2: Page test (happy path with mocked endpoints)**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { NewMeetingPage } from "./NewMeetingPage";

vi.mock("@/shared/api/endpoints", () => ({
  createMeeting: vi.fn(async () => ({ meetingId: "m1" })),
  updateMeetingGlossary: vi.fn(async () => undefined),
  attachMeetingDocument: vi.fn(async () => undefined),
  initFileUpload: vi.fn(),
  completeFileUpload: vi.fn(),
  abortFileUpload: vi.fn(),
  initAudioUpload: vi.fn(async () => ({ uploadId: "u1", parts: [{ partNumber: 1, presignedUrl: "https://x/1", expiresAt: "" }] })),
  completeAudioUpload: vi.fn(async () => ({ uploadId: "u1", status: "COMPLETED" })),
  searchDocuments: vi.fn(async () => []),
  createDocument: vi.fn(async () => ({ documentId: "d1" })),
}));

global.fetch = vi.fn(async () => ({ ok: true, status: 200, headers: new Headers({ etag: '"e1"' }) }) as any);

describe("NewMeetingPage", () => {
  it("requires title and audio before start", () => {
    render(<MemoryRouter><NewMeetingPage /></MemoryRouter>);
    expect(screen.getByTestId("start")).toBeDisabled();
  });
});
```

- [ ] **Step 3: Test passes**

```bash
npx vitest run src/pages/NewMeetingPage.test.tsx
npx tsc --noEmit
```

Expected: PASS, 0 tsc errors.

- [ ] **Step 4: Commit**

```bash
git add apps/ai-worker-web/src/pages/NewMeetingPage* apps/ai-worker-web/src/shared/api/endpoints.ts
git commit -m "feat(ai-worker-web): NewMeetingPage — single form, doc drops upload immediately, audio fires start"
```

---

### Task 6: MeetingDetailPage with SSE

**Files:**
- Create: `apps/ai-worker-web/src/pages/MeetingDetailPage.tsx`
- Create: `apps/ai-worker-web/src/pages/MeetingDetailPage.test.tsx`

- [ ] **Step 1: Implement page**

```tsx
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { createExport, getMeetingAggregate, pollExport } from "@/shared/api/endpoints";
import type { ExportJobDTO, MeetingAggregateDTO } from "@/shared/api/types";
import { SafeMarkdown } from "@/shared/markdown/SafeMarkdown";

const STEPS = [
  "AUDIO_PREPROCESS", "ASR", "ALIGNMENT", "DIARIZATION",
  "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE", "RAG_INDEXING",
  "SUMMARY", "EXTRACTION",
] as const;

export function MeetingDetailPage() {
  const { meetingId } = useParams<{ meetingId: string }>();
  const [agg, setAgg] = useState<MeetingAggregateDTO | null>(null);
  const [stepProgress, setStepProgress] = useState<Record<string, { status: string; progress: number }>>({});
  const [exportJob, setExportJob] = useState<ExportJobDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!meetingId) return;
    let es: EventSource | null = null;
    let cancelled = false;
    const load = async () => {
      try {
        const a = await getMeetingAggregate(meetingId);
        if (cancelled) return;
        setAgg(a);
        const taskId = a.latestTask?.data?.taskId;
        if (taskId) {
          es = new EventSource(`/api/processing-tasks/${taskId}/events`);
          es.addEventListener("step", (ev) => {
            const data = JSON.parse((ev as MessageEvent).data);
            setStepProgress((prev) => ({ ...prev, [data.step]: { status: data.status, progress: data.progress ?? 0 } }));
          });
          es.addEventListener("terminal", () => void load());
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    };
    void load();
    return () => { cancelled = true; if (es) es.close(); };
  }, [meetingId]);

  const handleExport = async () => {
    if (!meetingId) return;
    const job = await createExport(meetingId, "DOCX");
    setExportJob(job);
    for (let i = 0; i < 30; i++) {
      const p = await pollExport(meetingId, job.exportId);
      setExportJob(p);
      if (p.status === "SUCCEEDED" && p.downloadUrl) return;
      if (p.status === "FAILED" || p.status === "CANCELLED") {
        setError(`export ${p.status}`);
        return;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
  };

  return (
    <div className="stack">
      <header className="page-header"><h1 className="page-title">会议 {meetingId}</h1></header>
      {error && <div className="banner banner--danger" role="alert">{error}</div>}
      <section className="card stack" aria-labelledby="md-prog-h">
        <h2 id="md-prog-h">流水线进度</h2>
        <ul>
          {STEPS.map((s) => (
            <li key={s} data-testid={`step-${s}`}>
              {s} — {stepProgress[s]?.status ?? "pending"} ({Math.round((stepProgress[s]?.progress ?? 0) * 100)}%)
            </li>
          ))}
        </ul>
      </section>
      {agg?.speakers?.data && (
        <section className="card stack">
          <h2>说话人</h2>
          <ul>
            {agg.speakers.data.map((sp) => (
              <li key={sp.label}>
                {sp.label} — {sp.displayName || "未识别"} {sp.verificationStatus === "CONFIRMED" ? "（自动认定）" : ""}
              </li>
            ))}
          </ul>
        </section>
      )}
      {agg?.minutes?.data?.markdown && (
        <section className="card">
          <SafeMarkdown source={agg.minutes.data.markdown} />
        </section>
      )}
      <section className="card stack">
        <h2>导出 docx</h2>
        <button className="button button--primary" onClick={() => void handleExport()} data-testid="export-btn">
          创建导出
        </button>
        {exportJob && (
          <p>状态: <span data-testid="export-status">{exportJob.status}</span></p>
        )}
        {exportJob?.status === "SUCCEEDED" && exportJob.downloadUrl && (
          <a className="button" href={exportJob.downloadUrl} download data-testid="download-link">下载</a>
        )}
      </section>
    </div>
  );
}
```

- [ ] **Step 2: Page test (renders without SSE)**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { MeetingDetailPage } from "./MeetingDetailPage";

vi.mock("@/shared/api/endpoints", () => ({
  getMeetingAggregate: vi.fn(async () => ({ meeting: { meetingId: "m1" }, latestTask: { data: null }, speakers: { data: [] }, minutes: { data: null } })),
  createExport: vi.fn(),
  pollExport: vi.fn(),
}));

class FakeES { close = vi.fn(); addEventListener = vi.fn(); }
(globalThis as any).EventSource = FakeES;

describe("MeetingDetailPage", () => {
  it("renders all 10 pipeline steps", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>
    );
    await waitFor(() => expect(screen.getByTestId("step-ASR")).toBeInTheDocument());
    expect(screen.getByTestId("step-SUMMARY")).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Tests pass**

```bash
npx vitest run src/pages/MeetingDetailPage.test.tsx
npx tsc --noEmit
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add apps/ai-worker-web/src/pages/MeetingDetailPage*
git commit -m "feat(ai-worker-web): MeetingDetailPage with SSE step grid + export"
```

---

### Task 7: Delete wizard + retire MeetingWorkstationPage

**Files:**
- Delete: `apps/ai-worker-web/src/pages/MeetingWorkstationPage.tsx`
- Delete: `apps/ai-worker-web/src/pages/workstation/` (directory)
- Delete: `apps/ai-worker-web/src/features/wizard/` (directory)

- [ ] **Step 1: Remove files**

```bash
cd apps/ai-worker-web
rm src/pages/MeetingWorkstationPage.tsx
rm -r src/pages/workstation/
rm -r src/features/wizard/
```

- [ ] **Step 2: tsc — expect failures (imports broken)**

```bash
npx tsc --noEmit
```

Expected: errors pointing to App.tsx that still imports MeetingWorkstationPage. Continue to Task 8.

---

### Task 8: Rewrite App.tsx routes

**Files:**
- Modify: `apps/ai-worker-web/src/App.tsx`

- [ ] **Step 1: Replace App.tsx**

```tsx
import { Suspense, lazy } from "react";
import { NavLink, Route, Routes } from "react-router-dom";
import { useAuth } from "@/shared/auth/useAuth";
import { SkipLink } from "@/shared/components/SkipLink";
import { EnrollmentPage } from "@/pages/EnrollmentPage";
import { LoginPage } from "@/pages/LoginPage";
import { MeetingsPage } from "@/pages/MeetingsPage";

const NewMeetingPage = lazy(() => import("@/pages/NewMeetingPage").then((m) => ({ default: m.NewMeetingPage })));
const MeetingDetailPage = lazy(() => import("@/pages/MeetingDetailPage").then((m) => ({ default: m.MeetingDetailPage })));

export default function App() {
  const { ready, token } = useAuth();
  if (!ready) {
    return <div className="layout"><main className="layout__main" aria-busy="true" role="status">加载中…</main></div>;
  }
  return (
    <div className="layout">
      <SkipLink />
      <header className="layout__header">
        <strong className="layout__brand">运营工作站</strong>
        <nav className="layout__nav" aria-label="主导航">
          <NavLink to="/meetings" className={({ isActive }) => (isActive ? "active" : "")}>会议</NavLink>
          <NavLink to="/enrollment" className={({ isActive }) => (isActive ? "active" : "")}>声纹录入</NavLink>
        </nav>
        <span style={{ fontSize: 12, color: "var(--ink-3)" }}>{token ? "已登录" : "未登录"}</span>
      </header>
      <main id="main-content" className="layout__main">
        <Suspense fallback={<div aria-busy="true" role="status">加载中…</div>}>
          <Routes>
            <Route path="/" element={<MeetingsPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/meetings" element={<MeetingsPage />} />
            <Route path="/meetings/new" element={<NewMeetingPage />} />
            <Route path="/meetings/:meetingId" element={<MeetingDetailPage />} />
            <Route path="/enrollment" element={<EnrollmentPage />} />
          </Routes>
        </Suspense>
      </main>
    </div>
  );
}
```

- [ ] **Step 2: tsc passes**

```bash
npx tsc --noEmit
```

Expected: 0 errors.

- [ ] **Step 3: Full vitest**

```bash
npm test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add apps/ai-worker-web/src/App.tsx apps/ai-worker-web/src/pages apps/ai-worker-web/src/features
git commit -m "refactor(ai-worker-web): replace wizard with 3 independent pages"
```

---

### Task 9: Update e2e — happy paths

**Files:**
- Modify or replace: `apps/ai-worker-web/e2e/playwright.config.ts` (only if necessary)
- Create: `apps/ai-worker-web/e2e/enrollment-new-person.spec.ts`
- Create: `apps/ai-worker-web/e2e/new-meeting-end-to-end.spec.ts`
- Remove: old wizard-based e2e specs (search `e2e/` for spec files referencing wizard / steps).

- [ ] **Step 1: enrollment-new-person spec**

```ts
import { test, expect } from "@playwright/test";

test("enrollment new person happy path", async ({ page }) => {
  await page.route("**/admin/persons*", (route) => {
    if (route.request().method() === "GET") return route.fulfill({ status: 200, body: JSON.stringify({ success: true, data: [] }) });
    return route.fulfill({ status: 200, body: JSON.stringify({ success: true, data: { personId: "p-new", displayName: "李四" } }) });
  });
  await page.route("**/admin/enrollment/**", (route) => route.fulfill({ status: 200, body: JSON.stringify({ success: true, data: { sessionId: "s1", state: "PREVIEWED", qualityScore: 0.8 } }) }));
  await page.goto("/enrollment");
  await page.getByPlaceholder(/按姓名/).fill("李四");
  await page.getByRole("button", { name: /新建人员/ }).click();
  await page.getByLabel(/姓名/).fill("李四");
  await page.getByRole("button", { name: /创建/ }).click();
  await expect(page.getByText("李四").first()).toBeVisible();
});
```

- [ ] **Step 2: new-meeting-end-to-end spec**

```ts
import { test, expect } from "@playwright/test";

test("new meeting one-shot happy path", async ({ page }) => {
  await page.route("**/admin/meetings", (route) => {
    if (route.request().method() === "POST") {
      return route.fulfill({ status: 200, body: JSON.stringify({ success: true, data: { meetingId: "m-new" } }) });
    }
    return route.fulfill({ status: 200, body: JSON.stringify({ success: true, data: [] }) });
  });
  await page.route("**/admin/meetings/m-new", (route) =>
    route.fulfill({ status: 200, body: JSON.stringify({
      success: true,
      data: {
        meeting: { meetingId: "m-new", title: "季度评审" },
        latestTask: { data: { taskId: "task-1", phase: "TERMINAL", status: "SUCCEEDED" } },
        speakers: { data: [] },
        minutes: { data: { markdown: "# 会议纪要\n本测试纪要内容。" } },
      },
    }) }));
  await page.route("**/admin/meetings/m-new/glossary", (route) => route.fulfill({ status: 200, body: '{"success":true,"data":null}' }));
  await page.route("**/admin/documents/search*", (route) => route.fulfill({ status: 200, body: '{"success":true,"data":[]}' }));
  await page.route("**/admin/meetings/m-new/files/audio/uploads", (route) =>
    route.fulfill({ status: 200, body: JSON.stringify({ success: true, data: { uploadId: "u1", parts: [{ partNumber: 1, presignedUrl: "https://presign.test/audio/1", expiresAt: "" }] } }) }));
  await page.route("**/admin/meetings/m-new/files/audio/uploads/u1/complete", (route) =>
    route.fulfill({ status: 200, body: '{"success":true,"data":{"uploadId":"u1","status":"COMPLETED"}}' }));
  await page.route("https://presign.test/**", (route) =>
    route.fulfill({ status: 200, headers: { etag: '"e1"' }, body: "" }));

  await page.goto("/meetings/new");
  await page.getByLabel(/标题/).fill("季度评审");

  // attach a synthetic audio file
  const audioBuffer = Buffer.from(new Uint8Array(1024));
  await page.setInputFiles("#nm-audio", {
    name: "demo.mp3", mimeType: "audio/mpeg", buffer: audioBuffer,
  });

  await page.getByTestId("start").click();
  await expect(page).toHaveURL(/\/meetings\/m-new$/);
  await expect(page.getByTestId("step-ASR")).toBeVisible();
  await expect(page.getByText(/本测试纪要内容/)).toBeVisible();
});
```

- [ ] **Step 3: Run e2e**

```bash
npm run e2e
```

Expected: 2 passes (plus any retained existing specs).

- [ ] **Step 4: Commit**

```bash
git add apps/ai-worker-web/e2e/
git commit -m "test(ai-worker-web): e2e specs for new-person and new-meeting happy paths"
```

---

### Task 10: P4 phase gate

- [ ] **Step 1: Full TS + tests + build + e2e**

```bash
cd apps/ai-worker-web
npx tsc --noEmit
npm test
npm run build
npm run e2e
```

Expected: all green, gzip ≤ 200KB.

- [ ] **Step 2: Inspect bundle size**

```bash
ls -lh dist/assets/*.js
```

Confirm index chunk gzip stays below budget.

**P4 complete.**
