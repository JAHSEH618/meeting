# Meeting Web Full-Site Apple Glass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved C direction: a full-site Apple glass redesign for `apps/meeting-web`, including the login page, shell, meetings entry page, dense compliance pages, and workbench pages.

**Architecture:** Fix the CSS foundation first so `glass-design.css` is no longer overwritten by legacy `app.css` selectors, then migrate pages onto explicit page variants (`page--hero`, `page--workbench`, `page--dense`, `auth-page`). Keep existing React routing, auth, data fetching, and tests stable; changes are visual structure and class names only.

**Tech Stack:** React 18, TypeScript strict, Vite 5, Vitest, Testing Library, plain CSS.

---

## File Structure

- Modify: `apps/meeting-web/src/shared/styles/tokens.css`  
  Responsibility: global color, typography, spacing, radii, shadow, and backwards-compatible CSS variables.
- Modify: `apps/meeting-web/src/shared/styles/glass-design.css`  
  Responsibility: Apple glass visual primitives, page variants, auth layout, panels, buttons, modals, grids, stats, and tables.
- Modify: `apps/meeting-web/src/app/app.css`  
  Responsibility: meeting-web shell and business-specific layout utilities only. It must not redefine base `.card`, `.button`, `.modal-panel`, or `.data-table` styles that belong to `glass-design.css`.
- Modify: `apps/meeting-web/src/features/auth/LoginPage.tsx`  
  Responsibility: standalone full-screen auth entry using `auth-page`.
- Modify: `apps/meeting-web/src/features/auth/__tests__/LoginPage.test.tsx`  
  Responsibility: protect the login route and verify the Apple glass auth shell classes exist.
- Modify: `apps/meeting-web/src/app/App.tsx`  
  Responsibility: left rail shell and primary navigation.
- Modify: `apps/meeting-web/src/features/meetings/MeetingListPage.tsx`  
  Responsibility: hero-grade meetings entry page.
- Modify: `apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx`  
  Responsibility: protect meeting list rendering and `page--hero` entry layout.
- Modify: dense pages:
  - `apps/meeting-web/src/features/admin/LegalHoldsPage.tsx`
  - `apps/meeting-web/src/features/admin/DeletionJobsPage.tsx`
  - `apps/meeting-web/src/features/admin/BreakGlassPage.tsx`
  - `apps/meeting-web/src/features/admin/AuditEventsPage.tsx`
  - `apps/meeting-web/src/features/exports/ExportsPage.tsx`
- Modify: dense page tests:
  - `apps/meeting-web/src/features/admin/__tests__/LegalHoldsPage.test.tsx`
  - `apps/meeting-web/src/features/admin/__tests__/DeletionJobsPage.test.tsx`
  - `apps/meeting-web/src/features/admin/__tests__/BreakGlassPage.test.tsx`
  - `apps/meeting-web/src/features/admin/__tests__/AuditEventsPage.test.tsx`
  - `apps/meeting-web/src/features/exports/__tests__/ExportsPage.test.tsx`
- Modify: workbench pages:
  - `apps/meeting-web/src/features/meetings/MeetingDetailPage.tsx`
  - `apps/meeting-web/src/features/tasks/TaskProgressPage.tsx`
  - `apps/meeting-web/src/features/rag/RagPage.tsx`
  - `apps/meeting-web/src/features/documents/DocumentsPage.tsx`
  - `apps/meeting-web/src/features/transcript/TranscriptPage.tsx`
  - `apps/meeting-web/src/features/minutes/MinutesPage.tsx`
  - `apps/meeting-web/src/features/audio/AudioUploadPage.tsx`
  - `apps/meeting-web/src/features/items/ItemsPage.tsx`
  - `apps/meeting-web/src/features/speakers/SpeakerProfilesPage.tsx`
  - `apps/meeting-web/src/features/speakers/MeetingSpeakerConfirmPage.tsx`

---

### Task 1: Baseline and Visual Regression Class Tests

**Files:**
- Modify: `apps/meeting-web/src/features/auth/__tests__/LoginPage.test.tsx`
- Modify: `apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx`
- Modify: `apps/meeting-web/src/features/admin/__tests__/LegalHoldsPage.test.tsx`
- Modify: `apps/meeting-web/src/features/tasks/__tests__/TaskProgressPage.test.tsx`
- Modify: `apps/meeting-web/src/features/rag/__tests__/RagPage.test.tsx`

- [ ] **Step 1: Run the current focused tests before edits**

Run:

```bash
cd apps/meeting-web
npm test -- src/features/auth/__tests__/LoginPage.test.tsx src/features/meetings/__tests__/MeetingListPage.test.tsx src/features/admin/__tests__/LegalHoldsPage.test.tsx src/features/tasks/__tests__/TaskProgressPage.test.tsx src/features/rag/__tests__/RagPage.test.tsx
```

Expected: all selected tests pass before adding layout assertions.

- [ ] **Step 2: Add auth page class assertions**

In `apps/meeting-web/src/features/auth/__tests__/LoginPage.test.tsx`, update the test to capture the render result and assert the auth shell classes:

```tsx
const { container } = render(
  <TestRouter initialEntries={["/login"]}>
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/meetings" element={<div>meetings loaded</div>} />
    </Routes>
  </TestRouter>,
);

expect(container.querySelector(".auth-page")).toBeInTheDocument();
expect(container.querySelector(".auth-card")).toBeInTheDocument();
expect(screen.getByRole("heading", { name: "本地会议智能系统" })).toBeInTheDocument();
```

Keep the existing click and navigation assertion:

```tsx
fireEvent.click(screen.getByRole("button", { name: "登录" }));
await waitFor(() => expect(screen.getByText("meetings loaded")).toBeInTheDocument());
```

- [ ] **Step 3: Add meetings entry class assertions**

In `apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx`, capture `container` and assert the entry classes:

```tsx
const { container } = render(
  <TestRouter>
    <MeetingListPage />
  </TestRouter>,
);

expect(container.querySelector(".page--hero")).toBeInTheDocument();
expect(container.querySelector(".page-hero")).toBeInTheDocument();
expect(container.querySelector(".glass-panel--table")).toBeInTheDocument();
```

Keep the existing data assertions:

```tsx
await waitFor(() => expect(screen.getByText("产品周会")).toBeInTheDocument());
expect(screen.getByText("CREATED")).toBeInTheDocument();
```

- [ ] **Step 4: Add dense page class assertion to legal holds**

In `apps/meeting-web/src/features/admin/__tests__/LegalHoldsPage.test.tsx`, update the first test render and assert:

```tsx
const { container } = render(
  <TestRouter>
    <LegalHoldsPage />
  </TestRouter>,
);
expect(container.querySelector(".page--dense")).toBeInTheDocument();
expect(container.querySelector(".page-hero--compact")).toBeInTheDocument();
```

Keep:

```tsx
await waitFor(() => expect(screen.getByText(/暂无法定保全/)).toBeInTheDocument());
```

- [ ] **Step 5: Add workbench class assertions**

In `apps/meeting-web/src/features/tasks/__tests__/TaskProgressPage.test.tsx`, update the first test:

```tsx
const { container } = renderPage();
expect(container.querySelector(".page--workbench")).toBeInTheDocument();
expect(container.querySelector(".page-hero--workbench")).toBeInTheDocument();
```

In `apps/meeting-web/src/features/rag/__tests__/RagPage.test.tsx`, update the first test:

```tsx
const { container } = render(
  <TestRouter>
    <RagPage />
  </TestRouter>,
);
expect(container.querySelector(".page--workbench")).toBeInTheDocument();
expect(container.querySelector(".glass-panel")).toBeInTheDocument();
```

- [ ] **Step 6: Run focused tests and verify they fail for missing classes**

Run:

```bash
cd apps/meeting-web
npm test -- src/features/auth/__tests__/LoginPage.test.tsx src/features/meetings/__tests__/MeetingListPage.test.tsx src/features/admin/__tests__/LegalHoldsPage.test.tsx src/features/tasks/__tests__/TaskProgressPage.test.tsx src/features/rag/__tests__/RagPage.test.tsx
```

Expected: failures mention missing `.auth-page`, `.page--hero`, `.page--dense`, or `.page--workbench`.

- [ ] **Step 7: Keep the failing visual layout tests uncommitted until GREEN**

Do not commit the repository while these tests are failing. Keep the RED result as verification evidence, then commit the test assertions together with the implementation commit that turns them GREEN.

---

### Task 2: CSS Foundation and Override Repair

**Files:**
- Modify: `apps/meeting-web/src/shared/styles/tokens.css`
- Modify: `apps/meeting-web/src/shared/styles/glass-design.css`
- Modify: `apps/meeting-web/src/app/app.css`

- [ ] **Step 1: Add backwards-compatible Apple tokens**

In `apps/meeting-web/src/shared/styles/tokens.css`, keep the light Apple palette and add these variables inside `:root`:

```css
--surface-raised: rgba(255, 255, 255, 0.82);
--surface-sunken: rgba(0, 0, 0, 0.035);

--ink-4: #c7c7cc;

--primary-active: #0048b8;
--primary-ink: #004fbc;
--accent-active: #0048b8;
--accent-soft: var(--accent-light);
--accent-ink: #004fbc;

--success-ink: #1f8f3a;
--warn-ink: #a35a00;
--danger-ink: #b42318;

--line-3: rgba(0, 0, 0, 0.035);
--focus: var(--accent);
--radius-pill: 999px;
```

- [ ] **Step 2: Replace body background with a glass canvas**

In `tokens.css`, ensure `body` has no dark remnants:

```css
body {
  margin: 0;
  min-width: 320px;
  background: var(--bg);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--text-base);
  line-height: 1.6;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  overflow-x: hidden;
}
```

- [ ] **Step 3: Rebuild `glass-design.css` as the source of visual primitives**

Keep existing useful definitions, but ensure the file contains these core rules for page variants and glass panels:

```css
body::before {
  content: '';
  position: fixed;
  inset: 0;
  z-index: -1;
  background:
    radial-gradient(circle at 15% 18%, rgba(0, 102, 255, 0.11) 0%, transparent 38%),
    radial-gradient(circle at 86% 78%, rgba(0, 102, 255, 0.08) 0%, transparent 42%),
    radial-gradient(circle at 50% 46%, rgba(175, 82, 222, 0.045) 0%, transparent 52%),
    linear-gradient(135deg, #fafafa 0%, #f1f3f8 100%);
  pointer-events: none;
}

.page {
  flex: 1;
  min-width: 0;
  width: min(100%, 1800px);
  margin: 0 auto;
  padding: var(--space-16) var(--space-20);
  display: flex;
  flex-direction: column;
  gap: var(--space-12);
}

.page--hero {
  padding-top: var(--space-24);
  gap: var(--space-16);
}

.page--workbench {
  gap: var(--space-10);
}

.page--dense {
  gap: var(--space-8);
  padding-top: var(--space-12);
}

.page-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-8);
  align-items: end;
}

.page-hero--compact {
  align-items: start;
  padding-bottom: var(--space-4);
}

.page-hero__label {
  display: inline-flex;
  width: fit-content;
  margin-bottom: var(--space-5);
  padding: 8px 16px;
  border-radius: var(--radius-s);
  background: rgba(0, 102, 255, 0.07);
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.page-hero__title {
  margin: 0;
  font-size: clamp(40px, 5.8vw, 88px);
  font-weight: 850;
  line-height: 0.98;
  letter-spacing: -0.04em;
  max-width: 14ch;
}

.page--hero .page-hero__title {
  font-size: var(--text-hero);
}

.page--dense .page-hero__title {
  font-size: clamp(40px, 5vw, 64px);
  max-width: 18ch;
}

.page-hero__subtitle {
  margin: var(--space-4) 0 0;
  max-width: 62ch;
  color: var(--text-light);
  font-size: var(--text-md);
  line-height: 1.5;
}

.page--dense .page-hero__subtitle {
  font-size: var(--text-base);
}

.page-hero__actions {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
  justify-content: flex-end;
}

.glass-panel {
  background: var(--glass-card);
  backdrop-filter: var(--glass-card-blur);
  -webkit-backdrop-filter: var(--glass-card-blur);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: var(--radius-xl);
  padding: var(--space-12);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.95) inset, var(--shadow-2);
}

.glass-panel--hero {
  padding: var(--space-20);
  backdrop-filter: blur(50px) saturate(180%);
  -webkit-backdrop-filter: blur(50px) saturate(180%);
}

.glass-panel--compact {
  padding: var(--space-6);
  border-radius: var(--radius-l);
}

.glass-panel--table {
  padding: 0;
  overflow: hidden;
}
```

- [ ] **Step 4: Make base components Apple glass compatible**

In `glass-design.css`, define the canonical base styles:

```css
.card {
  background: var(--glass-card);
  backdrop-filter: var(--glass-card-blur);
  -webkit-backdrop-filter: var(--glass-card-blur);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: var(--radius-xl);
  padding: var(--space-8);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.95) inset, var(--shadow-1);
}

.button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: 44px;
  padding: 0 var(--space-5);
  border: 1px solid var(--line-1);
  border-radius: var(--radius-m);
  background: rgba(255, 255, 255, 0.78);
  color: var(--text);
  font-size: var(--text-sm);
  font-weight: 700;
  text-decoration: none;
  cursor: pointer;
  box-shadow: var(--shadow-1);
  transition: background-color 200ms ease, border-color 200ms ease, color 200ms ease, box-shadow 200ms ease, transform 200ms ease;
}

.button:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.95);
  transform: translateY(-2px);
  box-shadow: var(--shadow-2);
}

.button.primary,
.button--primary {
  background: var(--text);
  border-color: var(--text);
  color: white;
}

.button--ghost {
  background: rgba(255, 255, 255, 0.78);
  color: var(--text);
}

.button.danger,
.button--danger {
  background: var(--danger);
  border-color: var(--danger);
  color: white;
}
```

- [ ] **Step 5: Move duplicate legacy selectors out of `app.css`**

In `apps/meeting-web/src/app/app.css`, delete or narrow the existing base blocks for:

```text
.page
.page-header
.page-title
.page-subtitle
.card
.button
.button.primary
.button--primary
.button--ghost
.metric
.metric__label
.metric__value
.data-table
.modal-backdrop
.modal-panel
```

Keep shell-specific blocks and utility blocks that do not override glass primitives:

```text
.app-shell
.shell__rail
.shell__brand
.shell__rail-section
.shell__rail-link
.logout-btn
.shell__main
.tabbar
.field
.pill
.badge
.dot
.banner
.progress
.phase-strip
.empty-state
.drawer
.form-grid
.toolbar
.stack
feature-specific blocks
```

- [ ] **Step 6: Update shell styles in `app.css`**

Use this shell shape:

```css
.app-shell {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 100vh;
}

.shell__rail {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  background: var(--glass-header);
  backdrop-filter: var(--glass-header-blur);
  -webkit-backdrop-filter: var(--glass-header-blur);
  border-right: 1px solid var(--line-1);
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.shell__rail-link.active {
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-weight: 700;
}

.shell__main {
  display: flex;
  min-width: 0;
}
```

- [ ] **Step 7: Run CSS-focused build check**

Run:

```bash
cd apps/meeting-web
npm run build
```

Expected: TypeScript and Vite build pass. CSS warnings should not appear.

- [ ] **Step 8: Commit CSS foundation**

Run:

```bash
git add apps/meeting-web/src/shared/styles/tokens.css apps/meeting-web/src/shared/styles/glass-design.css apps/meeting-web/src/app/app.css
git commit -m "style(web): repair apple glass css foundation"
```

---

### Task 3: Login Page Full-Screen Auth Entry

**Files:**
- Modify: `apps/meeting-web/src/features/auth/LoginPage.tsx`
- Test: `apps/meeting-web/src/features/auth/__tests__/LoginPage.test.tsx`

- [ ] **Step 1: Replace LoginPage JSX with auth layout**

In `LoginPage.tsx`, keep all state and `onSubmit` logic. Replace only the returned JSX with:

```tsx
return (
  <main className="auth-page">
    <section className="auth-hero" aria-labelledby="auth-title">
      <span className="auth-hero__label">PRIVATE MEETING AI</span>
      <h1 id="auth-title" className="auth-hero__title">本地会议智能系统</h1>
      <p className="auth-hero__subtitle">
        转录、纪要、知识问答与合规留痕集中在一个本地工作台内完成。
      </p>
    </section>

    <section className="auth-card glass-panel" aria-label="登录">
      <div>
        <h2 className="auth-card__title">登录</h2>
        <p className="auth-card__subtitle">使用内置 MVP 账号进入会议处理工作台。</p>
      </div>
      <form className="auth-form form" onSubmit={onSubmit}>
        <div className="field">
          <label htmlFor="username">账号</label>
          <input id="username" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
        </div>
        <div className="field">
          <label htmlFor="password">密码</label>
          <input id="password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
        </div>
        {error ? <div className="error" role="alert">{error}</div> : null}
        <button className="button button--primary" type="submit" disabled={submitting || !username.trim() || !password}>
          {submitting ? "登录中" : "登录"}
        </button>
      </form>
    </section>
  </main>
);
```

- [ ] **Step 2: Ensure auth CSS exists**

In `glass-design.css`, add:

```css
.auth-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(420px, 0.95fr);
  align-items: center;
  gap: var(--space-16);
  width: min(100%, 1800px);
  margin: 0 auto;
  padding: var(--space-20);
}

.auth-hero__label {
  display: inline-flex;
  margin-bottom: var(--space-8);
  padding: 8px 16px;
  border-radius: var(--radius-s);
  background: rgba(0, 102, 255, 0.07);
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 800;
  letter-spacing: 0.14em;
}

.auth-hero__title {
  margin: 0;
  max-width: 12ch;
  font-size: clamp(56px, 8vw, 104px);
  font-weight: 900;
  line-height: 0.96;
  letter-spacing: -0.045em;
}

.auth-hero__subtitle {
  max-width: 48ch;
  margin: var(--space-8) 0 0;
  color: var(--text-light);
  font-size: var(--text-md);
}

.auth-card {
  width: min(520px, 100%);
  justify-self: end;
  display: grid;
  gap: var(--space-8);
}

.auth-card__title {
  margin: 0;
  font-size: var(--text-xl);
}

.auth-card__subtitle {
  margin: var(--space-3) 0 0;
  color: var(--text-light);
}

.auth-form {
  display: grid;
  gap: var(--space-5);
}
```

- [ ] **Step 3: Run focused login test**

Run:

```bash
cd apps/meeting-web
npm test -- src/features/auth/__tests__/LoginPage.test.tsx
```

Expected: test passes and the button text remains `登录`.

- [ ] **Step 4: Commit login page**

Run:

```bash
git add apps/meeting-web/src/features/auth/LoginPage.tsx apps/meeting-web/src/features/auth/__tests__/LoginPage.test.tsx apps/meeting-web/src/shared/styles/glass-design.css
git commit -m "style(web): redesign login as apple glass entry"
```

---

### Task 4: Shell and Meetings Hero Entry

**Files:**
- Modify: `apps/meeting-web/src/app/App.tsx`
- Modify: `apps/meeting-web/src/features/meetings/MeetingListPage.tsx`
- Modify: `apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx`
- Modify: `apps/meeting-web/src/shared/styles/glass-design.css`
- Modify: `apps/meeting-web/src/app/app.css`

- [ ] **Step 1: Update shell brand and primary action**

In `App.tsx`, update the rail brand and create button:

```tsx
<div className="shell__brand">Meeting</div>
<Link className="button button--primary shell__create" to="/meetings/new">新建会议</Link>
```

Do not change route paths or NavLink labels.

- [ ] **Step 2: Replace MeetingListPage layout**

In `MeetingListPage.tsx`, use this structure inside the return:

```tsx
<div className="page page--hero">
  <header className="page-hero">
    <div>
      <span className="page-hero__label">POWERED BY AI</span>
      <h1 className="page-hero__title">会议智能平台</h1>
      <p className="page-hero__subtitle">实时转录、结构化纪要、知识问答与合规留痕，集中在一个本地工作台中完成。</p>
    </div>
    <div className="page-hero__actions">
      <Link className="button button--primary" to="/meetings/new">创建会议</Link>
      <Link className="button button--ghost" to="/documents">文档库</Link>
    </div>
  </header>

  <section className="stats-grid" aria-label="会议概览">
    <div className="stat-card">
      <div className="stat-card__value">{stats.total}</div>
      <div className="stat-card__label">总会议数</div>
    </div>
    <div className="stat-card">
      <div className="stat-card__value">{stats.processing}</div>
      <div className="stat-card__label">处理中</div>
    </div>
    <div className="stat-card">
      <div className="stat-card__value">{stats.ready}</div>
      <div className="stat-card__label">已完成</div>
    </div>
  </section>

  <section className="glass-panel glass-panel--hero">
    <h2 className="glass-panel__title">实时 AI 会议助手</h2>
    <p className="glass-panel__body">自动沉淀会议内容、追踪处理状态，并把可检索的组织知识留在本地系统内。</p>
  </section>

  <section className="glass-panel glass-panel--table stack">
    <div className="meeting-list-toolbar">
      ...
    </div>
    ...
  </section>
</div>
```

Move the existing search field, loading/error/empty states, and table into the table panel.

- [ ] **Step 3: Add stat card CSS**

In `glass-design.css`, add:

```css
.stat-card {
  background: var(--glass-card);
  backdrop-filter: var(--glass-card-blur);
  -webkit-backdrop-filter: var(--glass-card-blur);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: var(--radius-xl);
  padding: var(--space-10);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.95) inset, var(--shadow-2);
}

.stat-card__value {
  color: var(--accent);
  font-size: 80px;
  font-weight: 900;
  letter-spacing: -0.04em;
  line-height: 1;
}

.stat-card__label {
  margin-top: var(--space-3);
  color: var(--text-light);
  font-size: var(--text-sm);
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.meeting-list-toolbar {
  padding: var(--space-6);
}
```

- [ ] **Step 4: Run focused meetings test**

Run:

```bash
cd apps/meeting-web
npm test -- src/features/meetings/__tests__/MeetingListPage.test.tsx
```

Expected: test passes, `产品周会` and `CREATED` still render.

- [ ] **Step 5: Commit shell and meetings entry**

Run:

```bash
git add apps/meeting-web/src/app/App.tsx apps/meeting-web/src/features/meetings/MeetingListPage.tsx apps/meeting-web/src/features/meetings/__tests__/MeetingListPage.test.tsx apps/meeting-web/src/shared/styles/glass-design.css apps/meeting-web/src/app/app.css
git commit -m "style(web): apply apple glass shell and meetings entry"
```

---

### Task 5: Dense Compliance Pages

**Files:**
- Modify dense page files listed in File Structure
- Modify dense page tests listed in File Structure

- [ ] **Step 1: Convert LegalHoldsPage to dense layout**

In `LegalHoldsPage.tsx`, change root:

```tsx
<main className="page page--dense">
```

Replace the header block with:

```tsx
<header className="page-hero page-hero--compact">
  <div>
    <span className="page-hero__label">COMPLIANCE</span>
    <h1 className="page-hero__title">法定保全</h1>
    <p className="page-hero__subtitle">放置 / 释放对会议、文档、声纹档案的法定保全。命中保全的对象不可被删除或导出。</p>
  </div>
  <div className="page-hero__actions">
    <button className="button button--primary" onClick={() => setShowCreate((v) => !v)} data-testid="toggle-create-legal-hold">
      {showCreate ? "取消创建" : "放置保全"}
    </button>
  </div>
</header>
```

Change create and list sections from `className="card"` to:

```tsx
className="glass-panel glass-panel--compact"
```

- [ ] **Step 2: Convert DeletionJobsPage**

Use root:

```tsx
<main className="page page--dense">
```

Use compact hero label `DATA RETENTION`; keep the existing button text and data-testid. Use `button button--danger` for the create button. Convert create/list sections to `glass-panel glass-panel--compact`.

- [ ] **Step 3: Convert BreakGlassPage**

Use root `page page--dense`, compact hero label `EMERGENCY ACCESS`, and convert sections to compact glass panels. Keep all current dialog behavior and test IDs.

- [ ] **Step 4: Convert AuditEventsPage**

Use root `page page--dense`, compact hero label `AUDIT TRAIL`, and convert filter/list sections to compact glass panels. Keep all inputs and test IDs unchanged.

- [ ] **Step 5: Convert ExportsPage**

Use root `page page--dense`, compact hero label `EXPORTS`, and convert list/create panels to compact glass panels. Keep export status text and cancel actions unchanged.

- [ ] **Step 6: Run dense page tests**

Run:

```bash
cd apps/meeting-web
npm test -- src/features/admin/__tests__/LegalHoldsPage.test.tsx src/features/admin/__tests__/DeletionJobsPage.test.tsx src/features/admin/__tests__/BreakGlassPage.test.tsx src/features/admin/__tests__/AuditEventsPage.test.tsx src/features/exports/__tests__/ExportsPage.test.tsx
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit dense pages**

Run:

```bash
git add apps/meeting-web/src/features/admin/LegalHoldsPage.tsx apps/meeting-web/src/features/admin/DeletionJobsPage.tsx apps/meeting-web/src/features/admin/BreakGlassPage.tsx apps/meeting-web/src/features/admin/AuditEventsPage.tsx apps/meeting-web/src/features/exports/ExportsPage.tsx apps/meeting-web/src/features/admin/__tests__/LegalHoldsPage.test.tsx apps/meeting-web/src/features/admin/__tests__/DeletionJobsPage.test.tsx apps/meeting-web/src/features/admin/__tests__/BreakGlassPage.test.tsx apps/meeting-web/src/features/admin/__tests__/AuditEventsPage.test.tsx apps/meeting-web/src/features/exports/__tests__/ExportsPage.test.tsx
git commit -m "style(web): apply dense apple glass compliance layouts"
```

---

### Task 6: Workbench Pages

**Files:**
- Modify workbench page files listed in File Structure
- Modify focused tests listed in File Structure

- [ ] **Step 1: Convert MeetingDetailPage**

Use:

```tsx
<div className="page page--workbench">
```

Replace `<header className="page-header">` with:

```tsx
<header className="page-hero page-hero--workbench">
  <div>
    <span className="page-hero__label">MEETING</span>
    <h1 className="page-hero__title">{meeting.title}</h1>
    <p className="page-hero__subtitle"><span translate="no">{meeting.meetingId}</span> · {meeting.language}</p>
  </div>
  <div className="page-hero__actions">
    <Link className="button button--primary" to={`/meetings/${meeting.meetingId}/audio`}>上传音频</Link>
    <Link className="button" to="/meetings">返回列表</Link>
  </div>
</header>
```

- [ ] **Step 2: Convert TaskProgressPage**

Use root `page page--workbench`, hero label `PROCESSING`, and keep the connection mode pill inside `page-hero__actions`. Convert metrics section to use `stats-grid` and `stat-card`; convert steps section to `glass-panel glass-panel--table stack`.

- [ ] **Step 3: Convert RagPage**

Use root `page page--workbench`, hero label `KNOWLEDGE`, question section `glass-panel`, and answer card `glass-panel stack`. Keep `aria-label="rag-answer"` on the answer container.

- [ ] **Step 4: Convert DocumentsPage and SpeakerProfilesPage**

Use root `page page--workbench`, hero labels `DOCUMENTS` and `VOICEPRINTS`, and change major cards/lists to `glass-panel`.

- [ ] **Step 5: Convert remaining meeting workflow pages**

Apply root `page page--workbench` and `page-hero page-hero--workbench` to:

```text
AudioUploadPage
TranscriptPage
MinutesPage
ItemsPage
MeetingSpeakerConfirmPage
```

Keep labels, role text, test IDs, and existing inline data behavior unchanged.

- [ ] **Step 6: Run workbench tests**

Run:

```bash
cd apps/meeting-web
npm test -- src/features/tasks/__tests__/TaskProgressPage.test.tsx src/features/rag/__tests__/RagPage.test.tsx src/features/documents/__tests__/DocumentsPage.test.tsx src/features/transcript/__tests__/TranscriptPage.test.tsx src/features/minutes/__tests__/MinutesPage.test.tsx src/features/audio/__tests__/AudioUploadPage.test.tsx src/features/items/__tests__/ItemsPage.test.tsx src/features/speakers/__tests__/SpeakerProfilesPage.test.tsx src/features/speakers/__tests__/MeetingSpeakerConfirmPage.test.tsx
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit workbench pages**

Run:

```bash
git add apps/meeting-web/src/features/meetings/MeetingDetailPage.tsx apps/meeting-web/src/features/tasks/TaskProgressPage.tsx apps/meeting-web/src/features/rag/RagPage.tsx apps/meeting-web/src/features/documents/DocumentsPage.tsx apps/meeting-web/src/features/transcript/TranscriptPage.tsx apps/meeting-web/src/features/minutes/MinutesPage.tsx apps/meeting-web/src/features/audio/AudioUploadPage.tsx apps/meeting-web/src/features/items/ItemsPage.tsx apps/meeting-web/src/features/speakers/SpeakerProfilesPage.tsx apps/meeting-web/src/features/speakers/MeetingSpeakerConfirmPage.tsx apps/meeting-web/src/features/tasks/__tests__/TaskProgressPage.test.tsx apps/meeting-web/src/features/rag/__tests__/RagPage.test.tsx
git commit -m "style(web): apply workbench apple glass layouts"
```

---

### Task 7: Full Verification and Browser Visual Check

**Files:**
- No planned source edits unless verification finds a specific issue.

- [ ] **Step 1: Run full meeting-web build**

Run:

```bash
cd apps/meeting-web
npm run build
```

Expected: `tsc -b && vite build` exits 0.

- [ ] **Step 2: Run full meeting-web tests**

Run:

```bash
cd apps/meeting-web
npm test
```

Expected: current full test suite passes. Existing React act/jsdom warnings can remain if exit code is 0.

- [ ] **Step 3: Run ai-worker-web guard build/test**

Run:

```bash
cd apps/ai-worker-web
npm run build
npm test
```

Expected: both exit 0. This confirms shared-style changes did not accidentally break the other frontend package.

- [ ] **Step 4: Start local meeting-web dev server**

Run:

```bash
cd apps/meeting-web
npm run dev -- --host 127.0.0.1
```

Expected: Vite prints a local URL, usually `http://127.0.0.1:5173/`.

- [ ] **Step 5: Browser visual smoke**

Open these routes:

```text
/login
/meetings
/admin/legal-holds
/admin/audit-events
/rag
```

Check:

```text
Login page: standalone full-screen auth layout, no left rail.
Meetings: 120px-scale hero, stats, glass panel table.
Legal holds and audit: compact hero, table visible in first screen on desktop.
RAG: workbench hero and glass question panel.
Mobile width: no horizontal text overlap, grid collapses to one column.
```

- [ ] **Step 6: Final git status**

Run:

```bash
git status --short --branch
```

Expected: clean working tree and branch ahead of `origin/master` by the implementation commits.

- [ ] **Step 7: Commit any final visual fix**

If visual smoke required a small fix, run:

```bash
git add apps/meeting-web
git commit -m "fix(web): polish apple glass responsive layout"
```

If no fix was needed, skip this commit.
