-- V202605270001: support exact-match person dedup probes.

CREATE INDEX IF NOT EXISTS idx_persons_tenant_displayname
  ON persons (tenant_id, display_name)
  WHERE deleted_at IS NULL AND status = 'ACTIVE';
