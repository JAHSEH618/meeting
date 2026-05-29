-- V202605270002: tenant-scoped generic multipart uploads for reference files.

BEGIN;

CREATE TABLE IF NOT EXISTS generic_file_upload_sessions (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  file_id text REFERENCES meeting_files(id),
  object_key text NOT NULL,
  bucket text NOT NULL,
  content_type text NOT NULL,
  file_name text NOT NULL,
  file_size_bytes bigint NOT NULL CHECK (file_size_bytes > 0),
  file_sha256 text NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
  part_size_bytes integer NOT NULL DEFAULT 8388608 CHECK (part_size_bytes >= 5242880),
  max_part_count integer NOT NULL DEFAULT 10000 CHECK (max_part_count BETWEEN 1 AND 10000),
  upload_status audio_upload_status NOT NULL DEFAULT 'INITIATED',
  created_by text REFERENCES users(id),
  expires_at timestamptz NOT NULL,
  completed_at timestamptz,
  aborted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT generic_file_upload_sessions_object_key_uk UNIQUE (tenant_id, object_key),
  CONSTRAINT generic_file_upload_sessions_completed_file_ck CHECK (
    (upload_status = 'COMPLETED' AND file_id IS NOT NULL AND completed_at IS NOT NULL)
    OR upload_status <> 'COMPLETED'
  ),
  CONSTRAINT generic_file_upload_sessions_terminal_time_ck CHECK (
    (upload_status = 'ABORTED' AND aborted_at IS NOT NULL)
    OR upload_status <> 'ABORTED'
  )
);

CREATE TABLE IF NOT EXISTS generic_file_upload_parts (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  upload_id text NOT NULL REFERENCES generic_file_upload_sessions(id),
  part_number integer NOT NULL CHECK (part_number BETWEEN 1 AND 10000),
  part_sha256 text NOT NULL CHECK (part_sha256 ~ '^[0-9a-f]{64}$'),
  size_bytes bigint NOT NULL CHECK (size_bytes > 0),
  etag text,
  upload_status audio_upload_status NOT NULL DEFAULT 'INITIATED',
  uploaded_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT generic_file_upload_parts_upload_part_uk UNIQUE (upload_id, part_number),
  CONSTRAINT generic_file_upload_parts_completed_etag_ck CHECK (
    (upload_status = 'COMPLETED' AND etag IS NOT NULL AND uploaded_at IS NOT NULL)
    OR upload_status <> 'COMPLETED'
  )
);

CREATE INDEX IF NOT EXISTS generic_file_upload_sessions_upload_idx
  ON generic_file_upload_sessions (tenant_id, id);

CREATE INDEX IF NOT EXISTS generic_file_upload_sessions_status_expires_idx
  ON generic_file_upload_sessions (tenant_id, upload_status, expires_at);

CREATE INDEX IF NOT EXISTS generic_file_upload_parts_upload_idx
  ON generic_file_upload_parts (tenant_id, upload_id, part_number);

DO $$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['generic_file_upload_sessions', 'generic_file_upload_parts']
  LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS set_updated_at ON %I', table_name);
    EXECUTE format('CREATE TRIGGER set_updated_at BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION public.set_updated_at()', table_name);
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %I USING (tenant_id = public.current_tenant_id()) WITH CHECK (tenant_id = public.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;

COMMIT;
