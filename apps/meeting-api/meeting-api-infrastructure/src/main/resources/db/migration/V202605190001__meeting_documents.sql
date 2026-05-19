-- Workstation D1: meeting_documents — many-to-many link between meetings and tenant-level documents.
-- A document can be referenced by multiple meetings; soft-deleted via deleted_at so the same
-- (meeting, document) pair can be re-attached later.

CREATE TABLE IF NOT EXISTS meeting_documents (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  meeting_id text NOT NULL REFERENCES meetings(id),
  document_id text NOT NULL REFERENCES documents(id),
  role text NOT NULL CHECK (role IN ('REFERENCE', 'ATTACHMENT')),
  attached_by text REFERENCES users(id),
  attached_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);

-- One active link per (meeting, document); soft-delete restores the slot.
CREATE UNIQUE INDEX IF NOT EXISTS meeting_documents_active_uk
  ON meeting_documents (meeting_id, document_id)
  WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS meeting_documents_tenant_meeting_idx
  ON meeting_documents (tenant_id, meeting_id);
CREATE INDEX IF NOT EXISTS meeting_documents_tenant_document_idx
  ON meeting_documents (tenant_id, document_id);

-- RLS — same pattern as initial_schema.sql's tenant_isolation loop.
ALTER TABLE meeting_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_documents FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON meeting_documents;
CREATE POLICY tenant_isolation ON meeting_documents
  USING (tenant_id = public.current_tenant_id())
  WITH CHECK (tenant_id = public.current_tenant_id());
