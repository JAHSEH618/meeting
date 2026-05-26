# Meeting Frontends Swedish Editorial Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `apps/meeting-web` and `apps/ai-worker-web` into cold Swedish editorial control surfaces that expose backend task, RAG, compliance, and worker/admin semantics clearly.

**Architecture:** Keep both apps independent. Add local CSS tokens and view helper classes in each app, then update key pages without changing API clients, generated DTOs, or backend contracts. Behavior changes are limited to URL-synced meeting filters and clearer state presentation.

**Tech Stack:** React 18, React Router 6, Vite 5, TypeScript strict, Vitest, React Testing Library, MSW.

---

## File Structure

- Modify `apps/meeting-web/src/app/app.css`: design tokens, shell, responsive layout, focus states, tables, status badges, progress/timeline, reduced motion.
- Modify `apps/meeting-web/src/app/App.tsx`: skip link, editorial shell header/nav, accessible loading fallback.
- Modify `apps/meeting-web/src/features/meetings/MeetingListPage.tsx`: URL-synced filters, Intl date formatting, table layout, empty state.
- Modify `apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx`: failing test for URL filter hydration and query updates.
- Modify `apps/meeting-web/src/features/meetings/MeetingDetailPage.tsx`: clearer overview/actions/task panel.
- Modify `apps/meeting-web/src/features/tasks/TaskProgressPage.tsx`: phase strip, source labels, terminal/error banners, tabular progress.
- Modify `apps/meeting-web/src/features/rag/RagPage.tsx`: split query/result layout and citation/status presentation.
- Modify `apps/meeting-web/src/features/transcript/TranscriptPage.tsx`: context/status layout and segment readability.
- Modify `apps/ai-worker-web/src/styles.css`: admin/workstation design tokens, shell, page/action panels, wizard layout, enrollment flow, focus states, reduced motion.
- Modify `apps/ai-worker-web/src/App.tsx`: skip link, admin shell copy, accessible fallback.
- Modify `apps/ai-worker-web/src/pages/MeetingsPage.tsx`: functional operator landing page.
- Create `apps/ai-worker-web/src/pages/__tests__/MeetingsPage.test.tsx`: landing page behavior test.
- Modify `apps/ai-worker-web/src/pages/MeetingWorkstationPage.tsx`: two-column workstation layout and clearer worker phase semantics.
- Modify `apps/ai-worker-web/src/pages/EnrollmentPage.tsx`: three-step enrollment flow, labels/name attributes, quality state.
- Create `apps/ai-worker-web/src/pages/__tests__/EnrollmentPage.test.tsx`: enrollment flow rendering test.

## Task 1: `meeting-web` URL Filters and Formatting

**Files:**
- Modify: `apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx`
- Modify: `apps/meeting-web/src/features/meetings/MeetingListPage.tsx`

- [ ] **Step 1: Write the failing URL filter test**

Add this test to `MeetingListPage.test.tsx`:

```tsx
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location-search">{location.search}</output>;
}

it("hydrates filters from the URL and writes changes back to query params", async () => {
  const user = userEvent.setup();
  render(
    <TestRouter initialEntries={["/meetings?securityLevel=SECRET&q=roadmap"]}>
      <MeetingListPage />
      <LocationProbe />
    </TestRouter>,
  );

  expect(await screen.findByLabelText("搜索会议")).toHaveValue("roadmap");
  expect(screen.getByLabelText("安全等级")).toHaveValue("SECRET");

  await user.clear(screen.getByLabelText("搜索会议"));
  await user.type(screen.getByLabelText("搜索会议"), "产品");
  await user.selectOptions(screen.getByLabelText("安全等级"), "INTERNAL");

  await waitFor(() => {
    expect(screen.getByTestId("location-search")).toHaveTextContent("q=%E4%BA%A7%E5%93%81");
    expect(screen.getByTestId("location-search")).toHaveTextContent("securityLevel=INTERNAL");
  });
});
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `npm test -- src/features/meetings/__tests__/MeetingListPage.test.tsx` in `apps/meeting-web`.

Expected: FAIL because `MeetingListPage` does not read or write URL search params.

- [ ] **Step 3: Implement URL-synced filters and Intl formatting**

In `MeetingListPage.tsx`, use `useSearchParams`. Initialize `keyword` from `q`, `securityLevel` from `securityLevel`, and update search params on input/select changes. Replace `toLocaleString("zh-CN")` with a module-level `Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" })`.

- [ ] **Step 4: Run the focused test**

Run: `npm test -- src/features/meetings/__tests__/MeetingListPage.test.tsx` in `apps/meeting-web`.

Expected: PASS.

## Task 2: `meeting-web` Design System and Shell

**Files:**
- Modify: `apps/meeting-web/src/app/app.css`
- Modify: `apps/meeting-web/src/app/App.tsx`
- Create: `apps/meeting-web/src/app/App.test.tsx`

- [ ] **Step 1: Write the failing shell accessibility test**

Create `apps/meeting-web/src/app/App.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { App } from "./App";

describe("App shell", () => {
  it("exposes a skip link and main landmark for authenticated routes", async () => {
    render(
      <TestRouter initialEntries={["/meetings"]}>
        <App />
      </TestRouter>,
    );

    expect(await screen.findByRole("link", { name: "跳到主内容" })).toHaveAttribute("href", "#main-content");
    expect(await screen.findByRole("main")).toHaveAttribute("id", "main-content");
  });
});
```

- [ ] **Step 2: Run the shell test and verify it fails**

Run: `npm test -- src/app/App.test.tsx` in `apps/meeting-web`.

Expected: FAIL because the shell has no skip link and its routed main content has no stable `id`.

- [ ] **Step 3: Update `App.tsx` shell**

Add `<a className="skip-link" href="#main-content">跳到主内容</a>`, refine header classes, keep existing routes unchanged, and make `RouteFallback` use `aria-live="polite"`.

- [ ] **Step 4: Replace `app.css` with the local design layer**

Add tokens for cold editorial surfaces, focus rings, status colors, tables, cards, badges, progress, page grids, form fields, responsive breakpoints, and `@media (prefers-reduced-motion: reduce)`.

- [ ] **Step 5: Run the focused test**

Run:

```bash
npm test -- src/app/App.test.tsx
npm test -- src/features/meetings/__tests__/MeetingListPage.test.tsx
```

Expected: PASS.

## Task 3: `meeting-web` Key Page Refactor

**Files:**
- Modify: `apps/meeting-web/src/features/meetings/MeetingListPage.tsx`
- Modify: `apps/meeting-web/src/features/meetings/MeetingDetailPage.tsx`
- Modify: `apps/meeting-web/src/features/tasks/TaskProgressPage.tsx`
- Modify: `apps/meeting-web/src/features/rag/RagPage.tsx`
- Modify: `apps/meeting-web/src/features/transcript/TranscriptPage.tsx`

- [ ] **Step 1: Add a task-source label assertion**

In `apps/meeting-web/src/features/tasks/__tests__/TaskProgressPage.test.tsx`, assert that the page renders human-readable ownership text:

```tsx
expect(await screen.findByText("Worker callback")).toBeInTheDocument();
```

- [ ] **Step 2: Run the task test and verify it fails**

Run: `npm test -- src/features/tasks/__tests__/TaskProgressPage.test.tsx` in `apps/meeting-web`.

Expected: FAIL because source values render as raw enum text.

- [ ] **Step 3: Implement page refactors**

Update page markup to use the new classes:

- `MeetingListPage`: `filter-bar`, `data-table`, status/security badge classes, improved empty state.
- `MeetingDetailPage`: `metric-grid`, `action-strip`, `workflow-panel`, no inline width styles.
- `TaskProgressPage`: phase cards, source label helper mapping `AI_WORKER_CALLBACK -> Worker callback`, `JAVA_TASK_SERVICE -> Java task service`, retry/cancel action strip, terminal state banner.
- `RagPage`: `rag-layout`, `query-panel`, `answer-panel`, `citation-card`, no inline citation styles.
- `TranscriptPage`: `status-banner`, `segment-list`, `segment-row`, progress/status panels.

- [ ] **Step 4: Run focused tests**

Run:

```bash
npm test -- src/features/tasks/__tests__/TaskProgressPage.test.tsx
npm test -- src/features/rag/__tests__/RagPage.test.tsx
npm test -- src/features/transcript/__tests__/TranscriptPage.test.tsx
```

Expected: PASS.

## Task 4: `ai-worker-web` Landing and Enrollment Tests

**Files:**
- Create: `apps/ai-worker-web/src/pages/__tests__/MeetingsPage.test.tsx`
- Create: `apps/ai-worker-web/src/pages/__tests__/EnrollmentPage.test.tsx`
- Modify: `apps/ai-worker-web/src/pages/MeetingsPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.tsx`

- [ ] **Step 1: Write the failing operator landing test**

Create `MeetingsPage.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { MeetingsPage } from "../MeetingsPage";

describe("MeetingsPage", () => {
  it("renders operator entry points for the workstation", () => {
    render(
      <MemoryRouter>
        <MeetingsPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole("heading", { name: "Worker Admin Workstation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "新建会议工作流" })).toHaveAttribute("href", "/meetings/new");
    expect(screen.getByText("会议列表等待 admin BFF 列表接口开放。")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Write the failing enrollment structure test**

Create `EnrollmentPage.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EnrollmentPage } from "../EnrollmentPage";

describe("EnrollmentPage", () => {
  it("renders the enrollment operation as three controlled steps", () => {
    render(<EnrollmentPage />);

    expect(screen.getByRole("heading", { name: "声纹录入" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "1. 选择人员" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "2. 上传并预览" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "3. 质量确认" })).toBeInTheDocument();
    expect(screen.getByLabelText("搜索人员")).toHaveAttribute("name", "personSearch");
  });
});
```

- [ ] **Step 3: Run tests and verify they fail**

Run: `npm test -- src/pages/__tests__/MeetingsPage.test.tsx src/pages/__tests__/EnrollmentPage.test.tsx` in `apps/ai-worker-web`.

Expected: FAIL because the tests/pages do not exist or headings/copy differ.

- [ ] **Step 4: Implement landing and enrollment markup**

Update `MeetingsPage.tsx` to render an operator landing with action panels for new meeting and enrollment, plus BFF list-unavailable state. Update `EnrollmentPage.tsx` headings, field names, panel classes, quality status, and button copy without changing endpoint calls.

- [ ] **Step 5: Run focused tests**

Run: `npm test -- src/pages/__tests__/MeetingsPage.test.tsx src/pages/__tests__/EnrollmentPage.test.tsx` in `apps/ai-worker-web`.

Expected: PASS.

## Task 5: `ai-worker-web` Workstation and Shell Refactor

**Files:**
- Modify: `apps/ai-worker-web/src/styles.css`
- Modify: `apps/ai-worker-web/src/App.tsx`
- Modify: `apps/ai-worker-web/src/pages/MeetingWorkstationPage.tsx`

- [ ] **Step 1: Add a workstation semantics assertion**

Create `apps/ai-worker-web/src/pages/__tests__/MeetingWorkstationPage.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { MeetingWorkstationPage } from "../MeetingWorkstationPage";

describe("MeetingWorkstationPage", () => {
  it("explains held worker processing before Java phase resume", () => {
    render(
      <MemoryRouter initialEntries={["/meetings/new"]}>
        <MeetingWorkstationPage />
      </MemoryRouter>,
    );

    expect(screen.getByText("Worker DAG 完成后保持 hold，确认说话人与上下文后再 resume Java phase。")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run existing wizard tests**

Run:

```bash
npm test -- src/pages/__tests__/MeetingWorkstationPage.test.tsx
npm test -- src/features/wizard/useWizard.test.ts
```

Expected: `MeetingWorkstationPage.test.tsx` FAILS because the held-processing copy is absent; `useWizard.test.ts` PASSES before markup changes.

- [ ] **Step 3: Implement shell and workstation layout**

Update `App.tsx` with skip link and admin shell classes. Update `styles.css` with editorial admin tokens, shell, page headers, action panels, workstation grid, wizard rail, operation panels, list rows, status pills, form fields, reduced motion. Update `MeetingWorkstationPage.tsx` to use `workstation-layout`, `wizard-rail`, `operation-panel`, and clearer phase copy while preserving all handlers and route-key behavior.

- [ ] **Step 4: Run focused tests**

Run:

```bash
npm test -- src/features/wizard/useWizard.test.ts
npm test -- src/pages/__tests__/MeetingWorkstationPage.test.tsx
npm test -- src/pages/__tests__/MeetingsPage.test.tsx src/pages/__tests__/EnrollmentPage.test.tsx
```

Expected: PASS.

## Task 6: Full Verification

**Files:**
- Modify only the frontend files already listed in earlier tasks when verification exposes a defect.

- [ ] **Step 1: Run `meeting-web` test and build gates**

Run in `apps/meeting-web`:

```bash
npm test
npm run build
```

Expected: PASS.

- [ ] **Step 2: Run `ai-worker-web` test and build gates**

Run in `apps/ai-worker-web`:

```bash
npm test
npm run build
```

Expected: PASS.

- [ ] **Step 3: Start both local Vite servers**

Run:

```bash
npm run dev -- --host 127.0.0.1
```

in `apps/meeting-web`, and:

```bash
npm run dev -- --host 127.0.0.1
```

in `apps/ai-worker-web`.

Expected: `meeting-web` serves on `http://127.0.0.1:5173`, `ai-worker-web` serves on `http://127.0.0.1:5174/workstation/`.

- [ ] **Step 4: Browser verification**

Open both apps in the Browser plugin. Verify desktop and mobile widths:

- `meeting-web`: `/meetings`, `/meetings/mtg_01`, `/rag`, and `/meetings/mtg_01/tasks/task_01`.
- `ai-worker-web`: `/workstation/meetings`, `/workstation/meetings/new`, `/workstation/enrollment`.

Expected: no overlapping text, visible focus states, cold editorial palette, responsive collapse, nonblank pages.

- [ ] **Step 5: Commit implementation**

Commit only touched frontend files and tests:

```bash
git add apps/meeting-web apps/ai-worker-web
git commit -m "feat: refactor meeting frontends editorial UI"
```

Expected: commit succeeds without staging unrelated `.claude-plugin-market/`.
