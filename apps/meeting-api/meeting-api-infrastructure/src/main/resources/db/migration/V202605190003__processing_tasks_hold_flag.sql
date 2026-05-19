-- Workstation D3: gate Java's SUMMARY/EXTRACTION behind an explicit resume-java-phase call.
-- When true, WorkerPhaseCompletedListener stops at WORKER_DAG_DONE and the user must
-- click "确认 → 生成纪要" in the workstation to promote the task into JAVA_LLM_RUNNING.

ALTER TABLE processing_tasks
  ADD COLUMN IF NOT EXISTS hold_at_worker_phase boolean NOT NULL DEFAULT false;
