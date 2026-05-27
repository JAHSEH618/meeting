# P1 — Contracts (OpenAPI + Error Codes + Codegen)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans.

**Goal:** Lock contracts for `POST /api/persons`, `POST /api/files` (generic multipart), and four new error codes. Regenerate all language artifacts.

**Working dir:** `packages/meeting-contracts/`

**Pre-flight:** `cd packages/meeting-contracts && npm install` (once).

---

### Task 1: Add error codes

**Files:**
- Modify: `packages/meeting-contracts/schemas/common/error-codes.yaml`

- [ ] **Step 1: Append four codes**

Append at end of `errorCodes:` list (use existing entries' format/i18n style as template):

```yaml
  # ── Person ────────────────────────────────────────────────────
  - code: PERSON_DISPLAY_NAME_REQUIRED
    step: VALIDATION
    retryable: false
    userMessage: 请填写姓名
    i18nKey: errors.PERSON_DISPLAY_NAME_REQUIRED
    opsTags: [person, validation]
  - code: PERSON_DUPLICATE
    step: VALIDATION
    retryable: false
    userMessage: 已存在同名人员
    i18nKey: errors.PERSON_DUPLICATE
    opsTags: [person, conflict]

  # ── Generic file upload ───────────────────────────────────────
  - code: FILE_UPLOAD_NOT_FOUND
    step: VALIDATION
    retryable: false
    userMessage: 上传会话不存在或已过期
    i18nKey: errors.FILE_UPLOAD_NOT_FOUND
    opsTags: [storage]
  - code: FILE_MIME_NOT_ALLOWED
    step: VALIDATION
    retryable: false
    userMessage: 不支持该文件类型
    i18nKey: errors.FILE_MIME_NOT_ALLOWED
    opsTags: [storage, validation]
```

- [ ] **Step 2: Validate**

```bash
cd packages/meeting-contracts
npm run check
```

Expected: ✔ Spectral lint OK · enum consistency OK.

- [ ] **Step 3: Commit**

```bash
git add packages/meeting-contracts/schemas/common/error-codes.yaml
git commit -m "contracts: add PERSON/FILE error codes for worker-web flows"
```

---

### Task 2: Add `POST /api/persons` to public-api.yaml

**Files:**
- Modify: `packages/meeting-contracts/openapi/public-api.yaml`

- [ ] **Step 1: Add Persons tag**

In the top-level `tags:` block (around line 20) append:

```yaml
  - name: Persons
    description: People referenced by speakers and meetings
```

- [ ] **Step 2: Add path block**

Insert before the `/speaker-profiles:` block (around line 760):

```yaml
  # ── Persons ─────────────────────────────────────────────────
  /persons:
    get:
      operationId: searchPersons
      description: Search persons by display name or email (debounced for typeahead).
      tags: [Persons]
      parameters:
        - in: query
          name: q
          schema: { type: string }
          required: false
        - $ref: '#/components/parameters/XRequestId'
        - $ref: '#/components/parameters/XTraceId'
      responses:
        '200':
          description: Person list
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PersonListResponse'
    post:
      operationId: createPerson
      description: Create a new person. Same-name persons return 409 PERSON_DUPLICATE with existing matches; client can resend with forceCreate=true to bypass.
      tags: [Persons]
      parameters:
        - $ref: '#/components/parameters/XRequestId'
        - $ref: '#/components/parameters/XTraceId'
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreatePersonRequest'
      responses:
        '200':
          description: Person created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PersonResponse'
        '409':
          description: Duplicate (same displayName exists)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PersonDuplicateResponse'
```

- [ ] **Step 3: Add component schemas**

In `components.schemas` (find existing schemas section), add:

```yaml
    PersonDTO:
      type: object
      required: [personId, displayName, createdAt]
      properties:
        personId: { type: string, format: uuid }
        displayName: { type: string, maxLength: 128 }
        email: { type: string, nullable: true, maxLength: 254 }
        externalId: { type: string, nullable: true, maxLength: 128 }
        createdAt: { type: string, format: date-time }
    PersonListResponse:
      allOf:
        - $ref: '#/components/schemas/ApiResponseBase'
        - type: object
          properties:
            data:
              type: array
              items: { $ref: '#/components/schemas/PersonDTO' }
    PersonResponse:
      allOf:
        - $ref: '#/components/schemas/ApiResponseBase'
        - type: object
          properties:
            data: { $ref: '#/components/schemas/PersonDTO' }
    CreatePersonRequest:
      type: object
      required: [displayName]
      properties:
        displayName: { type: string, minLength: 1, maxLength: 128 }
        email: { type: string, nullable: true, maxLength: 254 }
        externalId: { type: string, nullable: true, maxLength: 128 }
        forceCreate:
          type: boolean
          default: false
          description: When true, skip duplicate-name short-circuit.
    PersonDuplicateResponse:
      allOf:
        - $ref: '#/components/schemas/ApiResponseBase'
        - type: object
          properties:
            error:
              type: object
              required: [code, message, retryable, details]
              properties:
                code: { type: string, enum: [PERSON_DUPLICATE] }
                message: { type: string }
                retryable: { type: boolean }
                details:
                  type: object
                  properties:
                    matches:
                      type: array
                      items: { $ref: '#/components/schemas/PersonDTO' }
```

(If `ApiResponseBase` is not the exact existing envelope schema name, grep `public-api.yaml` for an existing list response and mirror its structure.)

- [ ] **Step 4: Validate**

```bash
cd packages/meeting-contracts
npm run lint:openapi
npm run check
```

Expected: ✔ no errors.

- [ ] **Step 5: Commit**

```bash
git add packages/meeting-contracts/openapi/public-api.yaml
git commit -m "contracts: add POST /api/persons + search endpoint"
```

---

### Task 3: Add `POST /api/files` generic multipart upload

**Files:**
- Modify: `packages/meeting-contracts/openapi/public-api.yaml`

- [ ] **Step 1: Add Files tag if missing**

Top-level `tags:` block — if `Files` tag doesn't exist (existing `[Files]` tags refer to meeting-scoped audio uploads; reuse the tag).

- [ ] **Step 2: Add path block**

Insert near the existing `/meetings/{meetingId}/files/audio/uploads` block (search around line 332). Add **above** that block:

```yaml
  # ── Generic file uploads (tenant-scoped, not meeting-scoped) ──
  /files:
    post:
      operationId: createFileUpload
      description: Initialize a tenant-scoped multipart upload (used for reference documents, NOT audio — use the meeting-scoped path for audio).
      tags: [Files]
      parameters:
        - $ref: '#/components/parameters/XRequestId'
        - $ref: '#/components/parameters/XTraceId'
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateFileUploadRequest'
      responses:
        '200':
          description: Upload session created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FileUploadSessionResponse'
        '415':
          description: MIME not allowed
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiErrorResponse'
  /files/{uploadId}/parts:
    post:
      operationId: createFileUploadPart
      description: Request a presigned URL for one part.
      tags: [Files]
      parameters:
        - in: path
          name: uploadId
          required: true
          schema: { type: string }
        - $ref: '#/components/parameters/XRequestId'
        - $ref: '#/components/parameters/XTraceId'
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateFileUploadPartRequest'
      responses:
        '200':
          description: Part presign returned
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FileUploadPartResponse'
  /files/{uploadId}/complete:
    post:
      operationId: completeFileUpload
      description: Mark all parts received, return durable fileId.
      tags: [Files]
      parameters:
        - in: path
          name: uploadId
          required: true
          schema: { type: string }
        - $ref: '#/components/parameters/XRequestId'
        - $ref: '#/components/parameters/XTraceId'
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CompleteFileUploadRequest'
      responses:
        '200':
          description: File ready
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FileUploadCompleteResponse'
  /files/{uploadId}/abort:
    post:
      operationId: abortFileUpload
      description: Discard a partially-uploaded session.
      tags: [Files]
      parameters:
        - in: path
          name: uploadId
          required: true
          schema: { type: string }
        - $ref: '#/components/parameters/XRequestId'
        - $ref: '#/components/parameters/XTraceId'
        - $ref: '#/components/parameters/IdempotencyKey'
      responses:
        '200':
          $ref: '#/components/responses/Ok'
```

- [ ] **Step 3: Add component schemas**

In `components.schemas`:

```yaml
    CreateFileUploadRequest:
      type: object
      required: [fileName, contentType, fileSizeBytes, fileSha256]
      properties:
        fileName: { type: string, minLength: 1, maxLength: 255 }
        contentType:
          type: string
          enum:
            - application/pdf
            - application/vnd.openxmlformats-officedocument.wordprocessingml.document
            - application/vnd.openxmlformats-officedocument.presentationml.presentation
            - text/plain
            - text/markdown
        fileSizeBytes: { type: integer, format: int64, minimum: 1, maximum: 524288000 }
        fileSha256: { type: string, pattern: '^[0-9a-f]{64}$' }
        partSizeBytes: { type: integer, format: int32, nullable: true, minimum: 1048576 }
    FileUploadSessionDTO:
      type: object
      required: [uploadId, parts]
      properties:
        uploadId: { type: string, format: uuid }
        parts:
          type: array
          items: { $ref: '#/components/schemas/FileUploadPartDTO' }
    FileUploadPartDTO:
      type: object
      required: [partNumber, presignedUrl, expiresAt]
      properties:
        partNumber: { type: integer, minimum: 1 }
        presignedUrl: { type: string, format: uri }
        expiresAt: { type: string, format: date-time }
    FileUploadSessionResponse:
      allOf:
        - $ref: '#/components/schemas/ApiResponseBase'
        - type: object
          properties:
            data: { $ref: '#/components/schemas/FileUploadSessionDTO' }
    CreateFileUploadPartRequest:
      type: object
      required: [partNumber, sizeBytes, partSha256]
      properties:
        partNumber: { type: integer, minimum: 1, maximum: 10000 }
        sizeBytes: { type: integer, format: int64, minimum: 1 }
        partSha256: { type: string, pattern: '^[0-9a-f]{64}$' }
    FileUploadPartResponse:
      allOf:
        - $ref: '#/components/schemas/ApiResponseBase'
        - type: object
          properties:
            data: { $ref: '#/components/schemas/FileUploadPartDTO' }
    CompleteFileUploadRequest:
      type: object
      required: [fileSha256, parts]
      properties:
        fileSha256: { type: string, pattern: '^[0-9a-f]{64}$' }
        parts:
          type: array
          items:
            type: object
            required: [partNumber, partSha256, etag]
            properties:
              partNumber: { type: integer, minimum: 1 }
              partSha256: { type: string, pattern: '^[0-9a-f]{64}$' }
              etag: { type: string }
    FileUploadCompleteResponse:
      allOf:
        - $ref: '#/components/schemas/ApiResponseBase'
        - type: object
          properties:
            data:
              type: object
              required: [fileId, sha256, sizeBytes, contentType]
              properties:
                fileId: { type: string, format: uuid }
                sha256: { type: string }
                sizeBytes: { type: integer, format: int64 }
                contentType: { type: string }
```

- [ ] **Step 4: Validate**

```bash
cd packages/meeting-contracts
npm run lint:openapi
npm run check
```

Expected: ✔ no errors.

- [ ] **Step 5: Commit**

```bash
git add packages/meeting-contracts/openapi/public-api.yaml
git commit -m "contracts: add POST /api/files generic multipart upload"
```

---

### Task 4: Regenerate all language artifacts

**Files:**
- Generated: `apps/meeting-web/src/shared/api/types.gen.ts`
- Generated: `apps/ai-worker/ai_worker/generated/*`
- Generated: `apps/meeting-api/meeting-api-client/generated/*`

- [ ] **Step 1: Codegen all targets**

```bash
cd packages/meeting-contracts
npm run codegen
```

Expected: completes with no errors.

- [ ] **Step 2: Check diff is contained**

```bash
git status
git diff --stat
```

Expected: changes only in three generated dirs above + nothing outside `packages/meeting-contracts/` / `apps/*/generated/` / `apps/meeting-web/src/shared/api/types.gen.ts`.

- [ ] **Step 3: Run zero-side-effect drift check**

```bash
cd packages/meeting-contracts
npm run codegen:check-temp
```

Expected: ✔ 0 diff.

- [ ] **Step 4: Commit all generated artifacts**

```bash
git add apps/meeting-web/src/shared/api/types.gen.ts apps/ai-worker/ai_worker/generated apps/meeting-api/meeting-api-client/generated
git commit -m "contracts: regen TS/Python/Java for persons + files"
```

---

### Task 5: Phase gate — final P1 verify

- [ ] **Step 1: Full contract check from clean state**

```bash
cd packages/meeting-contracts
npm run check
```

Expected: ✔ Spectral · ✔ JSON Schema · ✔ enum consistency · ✔ pipelineSteps guard · ✔ fixtures.

- [ ] **Step 2: Confirm no untracked drift**

```bash
git status
```

Expected: clean.

**P1 complete.** Move on to P2 / P3 / P4 in parallel.
