-- ──────────────────────────────────────────────────────────────────────────────
-- Phase 6.2/6.3 — persist ExportJob.renderOptions and capture row-creator audit
-- so an export-queue consumer retry produces identical output. JSONB column is
-- nullable for backfill; the application service always writes defaults.
-- ──────────────────────────────────────────────────────────────────────────────

BEGIN;

ALTER TABLE export_jobs
  ADD COLUMN IF NOT EXISTS render_options_json jsonb NOT NULL DEFAULT '{}'::jsonb;

-- Phase 7 will rename and tighten this; keep it permissive for now.
ALTER TABLE export_jobs
  ALTER COLUMN render_options_json DROP DEFAULT;

COMMIT;
