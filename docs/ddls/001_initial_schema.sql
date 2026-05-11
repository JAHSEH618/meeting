-- Initial PostgreSQL DDL for the local meeting intelligence system.
-- Source documents: docs/spec.md and docs/app-api-contracts.md.
-- Intended as a reviewable schema source for later Flyway/Liquibase migration split.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE OR REPLACE FUNCTION public.current_tenant_id()
RETURNS text
LANGUAGE sql
STABLE
AS $$
  SELECT NULLIF(current_setting('app.tenant_id', true), '')
$$;

CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;

DO $$
BEGIN
  CREATE TYPE security_level AS ENUM ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
  CREATE TYPE task_status AS ENUM (
    'PENDING',
    'QUEUED',
    'RUNNING',
    'ORPHANED',
    'PARTIAL_SUCCEEDED',
    'SUCCEEDED',
    'FAILED',
    'CANCEL_PENDING',
    'CANCELLED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
  CREATE TYPE processing_step AS ENUM (
    'AUDIO_UPLOAD',
    'AUDIO_PREPROCESS',
    'ASR',
    'ALIGNMENT',
    'DIARIZATION',
    'SPEAKER_EMBEDDING',
    'SPEAKER_MATCHING',
    'TRANSCRIPT_MERGE',
    'SUMMARY',
    'EXTRACTION',
    'RAG_INDEXING',
    'EXPORT'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
  CREATE TYPE step_status AS ENUM (
    'PENDING',
    'QUEUED',
    'RUNNING',
    'SUCCEEDED',
    'PARTIAL_SUCCEEDED',
    'FAILED',
    'SKIPPED',
    'CANCELLED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
  CREATE TYPE stale_status AS ENUM (
    'ACTIVE',
    'STALE',
    'REBUILD_QUEUED',
    'REBUILDING',
    'VALIDATING',
    'FAILED',
    'DELETED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
  CREATE TYPE content_status AS ENUM ('ACTIVE', 'DELETED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
  CREATE TYPE acceptance_status AS ENUM ('DRAFT', 'ACCEPTED', 'REJECTED', 'NEEDS_REVIEW');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS tenants (
  id text PRIMARY KEY,
  name text NOT NULL,
  status text NOT NULL DEFAULT 'ACTIVE',
  settings_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS users (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  email text NOT NULL,
  username text,
  password_hash text,
  display_name text NOT NULL,
  status text NOT NULL DEFAULT 'ACTIVE',
  last_login_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  CONSTRAINT users_email_per_tenant_uk UNIQUE (tenant_id, email)
);

CREATE TABLE IF NOT EXISTS roles (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  code text NOT NULL,
  name text NOT NULL,
  permissions_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT roles_code_per_tenant_uk UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS user_roles (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  user_id text NOT NULL REFERENCES users(id),
  role_id text NOT NULL REFERENCES roles(id),
  granted_by text REFERENCES users(id),
  granted_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz,
  CONSTRAINT user_roles_user_role_uk UNIQUE (tenant_id, user_id, role_id)
);

CREATE TABLE IF NOT EXISTS persons (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  display_name text NOT NULL,
  normalized_name text,
  external_ref text,
  department text,
  title text,
  email text,
  phone text,
  status text NOT NULL DEFAULT 'ACTIVE',
  aliases_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);

CREATE TABLE IF NOT EXISTS user_person_links (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  user_id text NOT NULL REFERENCES users(id),
  person_id text NOT NULL REFERENCES persons(id),
  link_type text NOT NULL DEFAULT 'PRIMARY',
  status text NOT NULL DEFAULT 'ACTIVE',
  is_primary boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT user_person_links_uk UNIQUE (tenant_id, user_id, person_id)
);

CREATE TABLE IF NOT EXISTS meetings (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  project_id text,
  title text NOT NULL,
  meeting_date date,
  scheduled_start_at timestamptz,
  meeting_timezone text NOT NULL DEFAULT 'Asia/Shanghai',
  status text NOT NULL DEFAULT 'CREATED',
  language text NOT NULL DEFAULT 'zh',
  security_level security_level NOT NULL DEFAULT 'INTERNAL',
  duration_seconds integer CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  quality_score numeric(5,2),
  transcript_version integer NOT NULL DEFAULT 0,
  minutes_version integer NOT NULL DEFAULT 0,
  rag_version integer NOT NULL DEFAULT 0,
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);

CREATE TABLE IF NOT EXISTS meeting_participants (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  person_id text REFERENCES persons(id),
  display_name_snapshot text,
  participant_role text NOT NULL DEFAULT 'PARTICIPANT',
  declared_attendance_status text NOT NULL DEFAULT 'DECLARED',
  detected_attendance_status text,
  source text NOT NULL DEFAULT 'MANUAL',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS meeting_files (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text REFERENCES meetings(id),
  file_type text NOT NULL,
  file_purpose text NOT NULL,
  file_name text,
  content_type text,
  bucket text NOT NULL,
  object_key text NOT NULL,
  uri text NOT NULL,
  size_bytes bigint CHECK (size_bytes IS NULL OR size_bytes >= 0),
  sha256 text,
  duration_ms bigint CHECK (duration_ms IS NULL OR duration_ms >= 0),
  upload_status text NOT NULL DEFAULT 'PENDING',
  storage_class text,
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  CONSTRAINT meeting_files_uri_uk UNIQUE (tenant_id, uri)
);

CREATE TABLE IF NOT EXISTS processing_tasks (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text REFERENCES meetings(id),
  task_type text NOT NULL,
  status task_status NOT NULL DEFAULT 'PENDING',
  progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  current_step processing_step,
  attempt_count integer NOT NULL DEFAULT 0,
  max_attempts integer NOT NULL DEFAULT 3,
  idempotency_key text,
  pipeline_version text,
  expected_input_version jsonb NOT NULL DEFAULT '{}'::jsonb,
  input_version jsonb NOT NULL DEFAULT '{}'::jsonb,
  input_hash text,
  parent_task_id text REFERENCES processing_tasks(id),
  lease_owner text,
  lease_expires_at timestamptz,
  heartbeat_at timestamptz,
  cancel_requested_at timestamptz,
  last_error_code text,
  last_error_message text,
  dlq_reason text,
  artifact_manifest_id text,
  trace_id text,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS processing_task_steps (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  task_id text NOT NULL REFERENCES processing_tasks(id),
  step_name processing_step NOT NULL,
  status step_status NOT NULL DEFAULT 'PENDING',
  progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  attempt_count integer NOT NULL DEFAULT 0,
  max_attempts integer NOT NULL DEFAULT 3,
  lease_owner text,
  lease_expires_at timestamptz,
  heartbeat_at timestamptz,
  input_hash text,
  output_hash text,
  error_code text,
  error_message text,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT processing_task_steps_attempt_uk UNIQUE (task_id, step_name, attempt_count)
);

CREATE TABLE IF NOT EXISTS callback_events (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  task_id text NOT NULL REFERENCES processing_tasks(id),
  step_name processing_step,
  worker_id text NOT NULL,
  attempt_no integer NOT NULL,
  lease_owner text,
  idempotency_key text NOT NULL,
  request_hash text NOT NULL,
  response_status integer,
  error_code text,
  request_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  response_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  trace_id text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT callback_events_idempotency_uk UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS artifact_manifests (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text REFERENCES meetings(id),
  task_id text REFERENCES processing_tasks(id),
  artifact_type text NOT NULL,
  artifact_uri text,
  artifact_hash text,
  input_artifact_hash text,
  input_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  output_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  models_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  prompt_template_id text,
  prompt_template_version text,
  provider text,
  model_version text,
  pipeline_version text,
  code_version text,
  data_boundary_policy_version text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transcript_segments (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  segment_index integer NOT NULL,
  start_ms bigint NOT NULL CHECK (start_ms >= 0),
  end_ms bigint NOT NULL CHECK (end_ms >= start_ms),
  speaker_label text NOT NULL,
  person_id text REFERENCES persons(id),
  speaker_name text,
  text text NOT NULL,
  original_text text NOT NULL,
  edited_text text,
  asr_confidence numeric(5,4),
  diarization_confidence numeric(5,4),
  speaker_confidence numeric(5,4),
  speaker_match_status text,
  edit_status text NOT NULL DEFAULT 'ORIGINAL',
  timestamp_precision text NOT NULL DEFAULT 'SEGMENT',
  evidence_version integer NOT NULL DEFAULT 1,
  transcript_version integer NOT NULL,
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT transcript_segments_order_uk UNIQUE (tenant_id, meeting_id, segment_index, transcript_version)
);

CREATE TABLE IF NOT EXISTS transcript_change_events (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  segment_id text REFERENCES transcript_segments(id),
  before_text text,
  after_text text,
  before_transcript_version integer NOT NULL,
  after_transcript_version integer NOT NULL,
  edit_reason text,
  changed_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS meeting_speakers (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  speaker_label text NOT NULL,
  global_speaker_label text,
  candidate_person_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  auto_match_score numeric(5,4),
  match_source text,
  verification_status text NOT NULL DEFAULT 'CANDIDATE',
  confirmed_person_id text REFERENCES persons(id),
  confirmed_by text REFERENCES users(id),
  confirmed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT meeting_speakers_label_uk UNIQUE (tenant_id, meeting_id, speaker_label)
);

CREATE TABLE IF NOT EXISTS speaker_profiles (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  person_id text NOT NULL REFERENCES persons(id),
  display_name_snapshot text,
  consent_status text NOT NULL DEFAULT 'ACTIVE',
  consent_source text,
  consent_version text,
  enrolled_by text REFERENCES users(id),
  revoked_at timestamptz,
  deleted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS speaker_enrollments (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  speaker_profile_id text NOT NULL REFERENCES speaker_profiles(id),
  source_audio_file_id text REFERENCES meeting_files(id),
  enrollment_status text NOT NULL DEFAULT 'PENDING',
  quality_score numeric(5,2),
  model_version text,
  artifact_uri text,
  error_code text,
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS speaker_embeddings (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  speaker_profile_id text NOT NULL REFERENCES speaker_profiles(id),
  person_id text NOT NULL REFERENCES persons(id),
  consent_status text NOT NULL DEFAULT 'ACTIVE',
  encryption_key_id text NOT NULL,
  wrapped_data_key bytea NOT NULL,
  encryption_algorithm text NOT NULL DEFAULT 'AES-256-GCM',
  embedding_ciphertext bytea NOT NULL,
  embedding_hash text NOT NULL,
  source_audio_file_id text REFERENCES meeting_files(id),
  quality_score numeric(5,2),
  model_version text NOT NULL,
  revoked_at timestamptz,
  deleted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS meeting_minutes (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  minutes_version integer NOT NULL,
  source_transcript_version integer NOT NULL,
  title text,
  markdown text NOT NULL,
  structured_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'DRAFT',
  stale_status stale_status NOT NULL DEFAULT 'ACTIVE',
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT meeting_minutes_version_uk UNIQUE (tenant_id, meeting_id, minutes_version)
);

CREATE TABLE IF NOT EXISTS meeting_action_items (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  origin text NOT NULL DEFAULT 'AI_EXTRACTED',
  title text NOT NULL,
  description text,
  owner_person_id text REFERENCES persons(id),
  owner_raw_text text,
  deadline_raw_text text,
  deadline_parsed date,
  deadline_timezone text,
  deadline_confidence numeric(5,4),
  deadline_resolution_rule text,
  priority text,
  status text NOT NULL DEFAULT 'OPEN',
  acceptance_status acceptance_status NOT NULL DEFAULT 'DRAFT',
  last_user_modified_at timestamptz,
  source_transcript_version integer,
  stale_status stale_status NOT NULL DEFAULT 'ACTIVE',
  evidence_segment_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  evidence_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  external_task_ref text,
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS meeting_decisions (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  title text NOT NULL,
  description text,
  status text NOT NULL DEFAULT 'PROPOSED',
  acceptance_status acceptance_status NOT NULL DEFAULT 'DRAFT',
  source_transcript_version integer,
  stale_status stale_status NOT NULL DEFAULT 'ACTIVE',
  evidence_segment_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  evidence_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS meeting_risks (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  title text NOT NULL,
  description text,
  severity text NOT NULL DEFAULT 'MEDIUM',
  status text NOT NULL DEFAULT 'OPEN',
  acceptance_status acceptance_status NOT NULL DEFAULT 'DRAFT',
  source_transcript_version integer,
  stale_status stale_status NOT NULL DEFAULT 'ACTIVE',
  evidence_segment_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  evidence_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS documents (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  project_id text,
  title text NOT NULL,
  file_id text REFERENCES meeting_files(id),
  document_type text NOT NULL,
  status text NOT NULL DEFAULT 'UPLOADED',
  security_level security_level NOT NULL DEFAULT 'INTERNAL',
  text_extraction_status text NOT NULL DEFAULT 'PENDING',
  source_uri text,
  content_hash text,
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);

CREATE TABLE IF NOT EXISTS document_chunks (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  document_id text NOT NULL REFERENCES documents(id),
  chunk_index integer NOT NULL,
  page_number integer,
  content text NOT NULL,
  content_hash text NOT NULL,
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT document_chunks_order_uk UNIQUE (tenant_id, document_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  project_id text,
  meeting_id text REFERENCES meetings(id),
  document_id text REFERENCES documents(id),
  source_type text NOT NULL,
  source_id text NOT NULL,
  source_segment_id text,
  content text NOT NULL,
  content_hash text NOT NULL,
  embedding vector(1024),
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  security_level security_level NOT NULL DEFAULT 'INTERNAL',
  chunk_version integer NOT NULL DEFAULT 1,
  transcript_version integer,
  minutes_version integer,
  chunk_strategy_version text,
  embedding_model_version text,
  status content_status NOT NULL DEFAULT 'ACTIVE',
  stale_status stale_status NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS knowledge_chunk_acl (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  chunk_id text NOT NULL REFERENCES knowledge_chunks(id),
  principal_type text NOT NULL,
  principal_id text NOT NULL,
  permission text NOT NULL DEFAULT 'READ',
  source_version text,
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT knowledge_chunk_acl_uk UNIQUE (tenant_id, chunk_id, principal_type, principal_id, permission)
);

CREATE TABLE IF NOT EXISTS rag_query_logs (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  user_id text REFERENCES users(id),
  query text NOT NULL,
  scope_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  top_k integer NOT NULL DEFAULT 8,
  answer_hash text,
  citations_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  latency_ms integer,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS prompt_templates (
  id text PRIMARY KEY,
  tenant_id text REFERENCES tenants(id),
  task_name text NOT NULL,
  version text NOT NULL,
  major_version integer NOT NULL DEFAULT 0,
  minor_version integer NOT NULL DEFAULT 1,
  patch_version integer NOT NULL DEFAULT 0,
  template_body text NOT NULL,
  json_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'DRAFT',
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT prompt_templates_version_uk UNIQUE (tenant_id, task_name, version)
);

CREATE TABLE IF NOT EXISTS llm_call_logs (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text REFERENCES meetings(id),
  task_id text REFERENCES processing_tasks(id),
  capability text,
  provider text NOT NULL,
  configured_model text,
  actual_model_version text,
  prompt_template_id text REFERENCES prompt_templates(id),
  prompt_template_version text,
  data_boundary_policy_version text,
  text_redaction_before_third_party_llm boolean NOT NULL DEFAULT false,
  security_level security_level NOT NULL,
  input_hash text NOT NULL,
  output_hash text,
  token_input integer,
  token_output integer,
  token_total integer,
  latency_ms integer,
  status text NOT NULL DEFAULT 'SUCCEEDED',
  error_code text,
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS llm_data_boundary_logs (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  llm_call_id text REFERENCES llm_call_logs(id),
  security_level security_level NOT NULL,
  text_redaction_before_third_party_llm boolean NOT NULL DEFAULT false,
  policy_version text NOT NULL,
  decision text NOT NULL,
  reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);

CREATE TABLE IF NOT EXISTS term_dictionaries (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  scope_type text NOT NULL,
  scope_id text,
  name text NOT NULL,
  version text NOT NULL,
  status text NOT NULL DEFAULT 'DRAFT',
  terms_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT term_dictionaries_version_uk UNIQUE (tenant_id, scope_type, scope_id, name, version)
);

CREATE TABLE IF NOT EXISTS model_registry (
  id text PRIMARY KEY,
  tenant_id text REFERENCES tenants(id),
  capability text NOT NULL,
  provider text NOT NULL,
  model_name text NOT NULL,
  configured_model text,
  actual_model_version text,
  checksum text,
  license_name text,
  dpa_status text,
  data_retention_policy text,
  training_use_policy text,
  cross_border_policy text,
  status text NOT NULL DEFAULT 'PENDING',
  approved_by text REFERENCES users(id),
  approved_at timestamptz,
  published_at timestamptz,
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS evaluation_runs (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  run_type text NOT NULL,
  target_type text NOT NULL,
  target_id text,
  dataset_ref text,
  metrics_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'PENDING',
  artifact_manifest_id text REFERENCES artifact_manifests(id),
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);

CREATE TABLE IF NOT EXISTS human_feedback (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text REFERENCES meetings(id),
  target_type text NOT NULL,
  target_id text NOT NULL,
  rating integer CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
  feedback_type text,
  comment text,
  correction_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS export_jobs (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text REFERENCES meetings(id),
  export_type text NOT NULL DEFAULT 'MEETING',
  format text NOT NULL,
  data_boundary_mode text,
  status text NOT NULL DEFAULT 'QUEUED',
  input_minutes_version integer,
  input_transcript_version integer,
  snapshot_manifest_id text REFERENCES artifact_manifests(id),
  watermark_text text,
  file_id text REFERENCES meeting_files(id),
  file_hash text,
  download_expires_at timestamptz,
  download_revoked_at timestamptz,
  error_code text,
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);

CREATE TABLE IF NOT EXISTS deletion_jobs (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  scope_type text NOT NULL,
  scope_id text NOT NULL,
  status text NOT NULL DEFAULT 'REQUESTED',
  requested_by text REFERENCES users(id),
  approved_by text REFERENCES users(id),
  legal_hold_checked boolean NOT NULL DEFAULT false,
  deleted_rows_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  deleted_files_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  kms_keys_destroyed_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  certificate_hash text,
  error_code text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);

CREATE TABLE IF NOT EXISTS deletion_certificates (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  deletion_job_id text NOT NULL REFERENCES deletion_jobs(id),
  scope_type text NOT NULL,
  scope_id text NOT NULL,
  object_hashes_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  deleted_rows_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  deleted_files_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  failed_items_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  certificate_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS legal_holds (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  scope_type text NOT NULL,
  scope_id text NOT NULL,
  reason text NOT NULL,
  requested_by text REFERENCES users(id),
  approved_by text REFERENCES users(id),
  status text NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  released_at timestamptz,
  released_by text REFERENCES users(id),
  release_reason text
);

CREATE TABLE IF NOT EXISTS audit_events (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  actor_user_id text REFERENCES users(id),
  actor_type text NOT NULL DEFAULT 'USER',
  action text NOT NULL,
  resource_type text NOT NULL,
  resource_id text,
  result text NOT NULL DEFAULT 'SUCCESS',
  reason text,
  ip_address inet,
  user_agent text,
  trace_id text,
  payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS domain_events_outbox (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  aggregate_type text NOT NULL,
  aggregate_id text NOT NULL,
  event_type text NOT NULL,
  event_version integer NOT NULL DEFAULT 1,
  payload_json jsonb NOT NULL,
  dedupe_key text NOT NULL,
  status text NOT NULL DEFAULT 'PENDING',
  retry_count integer NOT NULL DEFAULT 0,
  last_error text,
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT domain_events_outbox_dedupe_uk UNIQUE (tenant_id, dedupe_key)
);

CREATE UNIQUE INDEX IF NOT EXISTS processing_tasks_idempotency_uk
  ON processing_tasks (tenant_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS meetings_project_date_idx ON meetings (tenant_id, project_id, meeting_date);
CREATE INDEX IF NOT EXISTS meetings_status_idx ON meetings (tenant_id, status);
CREATE INDEX IF NOT EXISTS meetings_created_by_idx ON meetings (created_by, created_at);
CREATE INDEX IF NOT EXISTS meeting_files_meeting_idx ON meeting_files (tenant_id, meeting_id, file_type);
CREATE INDEX IF NOT EXISTS meeting_participants_meeting_idx ON meeting_participants (tenant_id, meeting_id);
CREATE INDEX IF NOT EXISTS processing_tasks_meeting_idx ON processing_tasks (tenant_id, meeting_id, created_at);
CREATE INDEX IF NOT EXISTS processing_tasks_status_idx ON processing_tasks (tenant_id, status, current_step);
CREATE INDEX IF NOT EXISTS processing_task_steps_task_idx ON processing_task_steps (tenant_id, task_id, step_name);
CREATE INDEX IF NOT EXISTS callback_events_task_idx ON callback_events (tenant_id, task_id, created_at);
CREATE INDEX IF NOT EXISTS transcript_segments_meeting_idx ON transcript_segments (tenant_id, meeting_id, segment_index);
CREATE INDEX IF NOT EXISTS transcript_segments_time_idx ON transcript_segments (meeting_id, start_ms);
CREATE INDEX IF NOT EXISTS transcript_segments_person_idx ON transcript_segments (meeting_id, person_id);
CREATE INDEX IF NOT EXISTS meeting_speakers_meeting_idx ON meeting_speakers (tenant_id, meeting_id);
CREATE INDEX IF NOT EXISTS speaker_profiles_person_idx ON speaker_profiles (tenant_id, person_id, consent_status);
CREATE INDEX IF NOT EXISTS speaker_embeddings_profile_idx ON speaker_embeddings (tenant_id, speaker_profile_id, consent_status);
CREATE INDEX IF NOT EXISTS meeting_minutes_meeting_idx ON meeting_minutes (tenant_id, meeting_id, minutes_version);
CREATE INDEX IF NOT EXISTS meeting_action_items_meeting_idx ON meeting_action_items (tenant_id, meeting_id, status, stale_status);
CREATE INDEX IF NOT EXISTS meeting_decisions_meeting_idx ON meeting_decisions (tenant_id, meeting_id, stale_status);
CREATE INDEX IF NOT EXISTS meeting_risks_meeting_idx ON meeting_risks (tenant_id, meeting_id, stale_status);
CREATE INDEX IF NOT EXISTS documents_project_idx ON documents (tenant_id, project_id, status);
CREATE INDEX IF NOT EXISTS document_chunks_document_idx ON document_chunks (tenant_id, document_id, chunk_index);
CREATE INDEX IF NOT EXISTS knowledge_chunks_project_source_idx ON knowledge_chunks (tenant_id, project_id, source_type);
CREATE INDEX IF NOT EXISTS knowledge_chunks_source_idx ON knowledge_chunks (meeting_id, source_id);
CREATE INDEX IF NOT EXISTS knowledge_chunks_status_meeting_idx ON knowledge_chunks (tenant_id, status, meeting_id);
CREATE INDEX IF NOT EXISTS knowledge_chunks_hash_idx ON knowledge_chunks (content_hash);
CREATE INDEX IF NOT EXISTS knowledge_chunks_content_gin_idx ON knowledge_chunks USING gin (to_tsvector('simple', content));
CREATE INDEX IF NOT EXISTS knowledge_chunks_embedding_hnsw_idx
  ON knowledge_chunks USING hnsw (embedding vector_cosine_ops)
  WHERE status = 'ACTIVE' AND stale_status = 'ACTIVE' AND embedding IS NOT NULL;
CREATE INDEX IF NOT EXISTS rag_query_logs_created_idx ON rag_query_logs (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS llm_call_logs_meeting_idx ON llm_call_logs (tenant_id, meeting_id, created_at);
CREATE INDEX IF NOT EXISTS llm_data_boundary_logs_call_idx ON llm_data_boundary_logs (tenant_id, llm_call_id);
CREATE INDEX IF NOT EXISTS artifact_manifests_task_idx ON artifact_manifests (tenant_id, task_id, created_at);
CREATE INDEX IF NOT EXISTS export_jobs_meeting_idx ON export_jobs (tenant_id, meeting_id, status);
CREATE INDEX IF NOT EXISTS deletion_jobs_scope_idx ON deletion_jobs (tenant_id, scope_type, scope_id, status);
CREATE INDEX IF NOT EXISTS legal_holds_scope_idx ON legal_holds (tenant_id, scope_type, scope_id, status);
CREATE INDEX IF NOT EXISTS audit_events_resource_idx ON audit_events (tenant_id, resource_type, resource_id, created_at);
CREATE INDEX IF NOT EXISTS domain_events_outbox_status_idx ON domain_events_outbox (status, created_at);

DO $$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'tenants',
    'users',
    'roles',
    'persons',
    'user_person_links',
    'meetings',
    'meeting_participants',
    'meeting_files',
    'processing_tasks',
    'processing_task_steps',
    'transcript_segments',
    'meeting_speakers',
    'speaker_profiles',
    'speaker_enrollments',
    'meeting_minutes',
    'meeting_action_items',
    'meeting_decisions',
    'meeting_risks',
    'documents',
    'prompt_templates',
    'term_dictionaries',
    'model_registry',
    'export_jobs',
    'deletion_jobs',
    'domain_events_outbox'
  ]
  LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS set_updated_at ON %I', table_name);
    EXECUTE format('CREATE TRIGGER set_updated_at BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION public.set_updated_at()', table_name);
  END LOOP;
END $$;

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_self ON tenants;
CREATE POLICY tenant_self ON tenants
  USING (id = public.current_tenant_id())
  WITH CHECK (id = public.current_tenant_id());

DO $$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'users',
    'roles',
    'user_roles',
    'persons',
    'user_person_links',
    'meetings',
    'meeting_participants',
    'meeting_files',
    'processing_tasks',
    'processing_task_steps',
    'callback_events',
    'artifact_manifests',
    'transcript_segments',
    'transcript_change_events',
    'meeting_speakers',
    'speaker_profiles',
    'speaker_enrollments',
    'speaker_embeddings',
    'meeting_minutes',
    'meeting_action_items',
    'meeting_decisions',
    'meeting_risks',
    'documents',
    'document_chunks',
    'knowledge_chunks',
    'knowledge_chunk_acl',
    'rag_query_logs',
    'llm_call_logs',
    'llm_data_boundary_logs',
    'term_dictionaries',
    'evaluation_runs',
    'human_feedback',
    'export_jobs',
    'deletion_jobs',
    'deletion_certificates',
    'legal_holds',
    'audit_events',
    'domain_events_outbox'
  ]
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %I USING (tenant_id = public.current_tenant_id()) WITH CHECK (tenant_id = public.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;

ALTER TABLE prompt_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE prompt_templates FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_or_global ON prompt_templates;
CREATE POLICY tenant_or_global ON prompt_templates
  USING (tenant_id IS NULL OR tenant_id = public.current_tenant_id())
  WITH CHECK (tenant_id IS NULL OR tenant_id = public.current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON model_registry;
DROP POLICY IF EXISTS tenant_or_global ON model_registry;
CREATE POLICY tenant_or_global ON model_registry
  USING (tenant_id IS NULL OR tenant_id = public.current_tenant_id())
  WITH CHECK (tenant_id IS NULL OR tenant_id = public.current_tenant_id());

COMMIT;
