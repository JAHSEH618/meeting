# Meeting Frontends Swedish Editorial Refactor Design

## Scope

Refactor both React frontends:

- `apps/meeting-web`: the business SPA for meetings, upload, processing progress, transcript, minutes, RAG, exports, speakers, and compliance.
- `apps/ai-worker-web`: the worker/admin workstation for meeting orchestration, speaker enrollment, glossary/document attachment, held processing, Java phase resume, and export checks.

The refactor is UI and frontend structure only. It must not change Public API, admin BFF contracts, generated DTOs, backend behavior, auth semantics, or storage/security boundaries.

## Product Model

The two apps should look related but communicate different jobs.

`meeting-web` is a business control console. It should help users understand what is true in the business system: meeting status, task phase, stale downstream artifacts, LLM security blocking, citation coverage, export state, legal holds, deletion jobs, break-glass, and audit results.

`ai-worker-web` is an operator workstation. It should make the controlled pipeline explicit: create or open a meeting, attach glossary/documents, start worker processing with hold semantics, inspect speaker candidates, finalize/resume the Java phase, and create or poll exports. It should read as an internal operations tool, not a consumer dashboard.

## Visual Direction

Use a cold Swedish editorial style:

- Palette: off-white and ice gray backgrounds, slate ink text, restrained blue accents, muted cyan/steel status tints, and sparse red/amber for exceptional states.
- Layout: clean editorial grids, narrow typographic hierarchy, dense but calm tables, quiet section bands, and minimal decoration.
- Shape: 6-8px radius maximum, thin borders, no nested cards, no gradient orbs, no decorative blobs.
- Typography: compact headings, readable body text, tabular numerals for progress, timestamps, counts, and confidence scores.
- Interaction: clear hover, active, disabled, and `:focus-visible` states; no hidden focus outlines; reduced-motion support for every animation.

The design should avoid a one-note blue theme. Blue can be the accent, while neutral surfaces, slate text, cyan status, amber warning, red danger, and green success provide functional contrast.

## Shared Frontend System

Each app keeps its own CSS and build. Do not introduce a shared package in this pass.

Add a small local design layer in both apps:

- CSS tokens for surfaces, borders, text, accent, status colors, focus rings, spacing, radii, shadows, and font stacks.
- Reusable class patterns for shell, page header, actions, button variants, form fields, filters, cards, metric tiles, data tables, badges, status pills, banners, progress bars, split layouts, and empty states.
- A skip link and consistent `main` landmark.
- Robust long-text handling with `min-width: 0`, `overflow-wrap`, `text-wrap`, and table cell truncation where appropriate.

The apps may share naming conventions but should not require cross-workspace imports.

## Backend-Aligned UX Requirements

`meeting-web` must surface backend facts rather than flatten them:

- Processing task status and phase: distinguish worker DAG, worker done, Java LLM, terminal, and SSE versus polling connection mode.
- Step ownership: make `AI_WORKER_CALLBACK` and `JAVA_TASK_SERVICE` understandable without implying worker retry for Java-owned steps.
- STALE: show downstream stale state on minutes, items, RAG, and exports; offer regeneration/reindex/export actions only where the existing backend supports them.
- Security level blocking: `SECURITY_LEVEL_BLOCKED` keeps the fixed phase-1 business message.
- RAG: show coverage and citation quality; distinguish transcript-only coverage from full meeting/document coverage.
- Compliance: legal hold, deletion job, break-glass, and audit pages should use administrative table/list patterns, not generic cards.

`ai-worker-web` must make operator semantics clear:

- The workstation wizard shows each phase as an operational lane: metadata, audio upload handoff to Java, glossary, documents, held processing, speaker confirmation, finalize/resume Java phase, export.
- `startMeetingProcessing` remains a held workflow; the UI copy should explain that Java summary/extraction waits for finalization.
- Speaker enrollment shows person selection, session state, file selection, preview quality, quality threshold, and commit eligibility.
- Export creation and polling state are visible, with download availability separate from job success.
- Admin BFF errors should show code/message/retryable status without dumping unknown details.

## Page-Level Design

### `meeting-web`

Refactor the shell into a restrained console layout:

- Top navigation with brand, primary domains, and active route states.
- Main content width optimized for dense workflows, with responsive collapse under mobile widths.
- Page headers with title, context metadata, and right-aligned primary actions.

Prioritize these pages:

- `MeetingListPage`: URL-synced keyword/security filters, denser table, status/security badges, explicit empty states, Intl date formatting.
- `MeetingDetailPage`: meeting overview with status, security, transcript/minutes versions, primary workflow actions, and a clearer processing task creation panel.
- `TaskProgressPage`: task phase strip, connection mode, retry/cancel actions, step timeline/table, ownership labels, progress bars, terminal/error state banners.
- `TranscriptPage`: persistent context header, clear processing/failed/stale notices, readable segment list, focused citation highlight with reduced motion.
- `RagPage`: split query/results layout on desktop, scope controls as fieldsets, coverage banner, citation blocks with meeting/document distinction.
- `MinutesPage`, `ItemsPage`, `ExportsPage`, and admin pages: apply the same system and preserve existing business prompts/tests.

### `ai-worker-web`

Refactor into an admin workstation:

- Shell header identifies this as the worker/admin surface, with login state and concise navigation.
- `MeetingsPage` becomes a functional operator landing page: explain available entry points through action panels and show that meeting listing is unavailable until the admin BFF exposes a list endpoint.
- `MeetingWorkstationPage` becomes a two-column workstation on desktop: wizard rail/status on the left, active step surface on the right. Long lists use existing `VirtualList`.
- `EnrollmentPage` becomes a three-step operational flow with person search, enrollment session, upload/preview, quality assessment, and commit action.

## Accessibility and Web Interface Guidelines

Apply the fetched Web Interface Guidelines across touched files:

- Add skip link and visible `:focus-visible` styles.
- Form controls have labels, meaningful `name`, appropriate `type`, autocomplete policy, and examples ending in `…`.
- URL should reflect stateful filters on key pages, especially meeting search/security filters.
- Use `Intl.DateTimeFormat` and `Intl.NumberFormat` for dates, percentages, and counts.
- Loading text ends with `…`.
- Buttons are for actions; links are for navigation and downloads.
- Destructive actions keep confirmation behavior already present; do not introduce immediate destructive actions.
- Large lists keep virtualization or `content-visibility` style containment.
- Reduced motion disables citation flashes and spinner-style animations.

## Data Flow

No new API data layer is required.

`meeting-web` continues to use `src/shared/api/client.ts` and existing feature state. The task SSE subscription and polling fallback remain unchanged. UI-only changes can add formatters and small view helpers.

`ai-worker-web` continues to use `src/shared/api/endpoints.ts`. The wizard state remains in `useWizard`. UI changes may add derived labels and status metadata, but not change API call ordering.

## Error Handling

Keep stable error-code mapping in `meeting-web`.

For `ai-worker-web`, keep `ApiError` formatting but improve presentation:

- Show retryable status as a status pill.
- Keep raw unknown errors to a short string.
- Do not render arbitrary details objects.

All async error regions need `role="alert"` or `aria-live` matching severity.

## Testing and Verification

Required verification:

- `npm test` in `apps/meeting-web`.
- `npm run build` or at least `npm run type-check` in `apps/meeting-web`.
- `npm test` and `npm run build` in `apps/ai-worker-web`.
- Browser verification for both apps through local Vite servers after implementation, including desktop and mobile viewport screenshots for the key pages that have mockable data.

Focused tests should be added only where behavior changes:

- URL-synced meeting list filters.
- Task progress still renders phase/source/progress and SSE fallback tests still pass.
- RAG coverage/citation rendering remains intact.
- ai-worker wizard still keys route meeting IDs and preserves existing tests.

## Non-Goals

- No new backend endpoints.
- No schema/codegen changes.
- No TanStack Query migration in this pass.
- No cross-app package extraction.
- No redesign of auth token storage.
- No new image assets or hero/marketing pages.

## Risks

- Broad CSS changes can regress tests that query by text or class-sensitive snapshots. Keep semantic text stable where tests expect it.
- `ai-worker-web` currently has hand-written admin BFF DTOs. Avoid tightening types beyond existing contract behavior.
- Some pages use inline styles and large single files. Refactor only where it directly supports the visual/system changes; avoid broad component extraction unless needed for readability.
