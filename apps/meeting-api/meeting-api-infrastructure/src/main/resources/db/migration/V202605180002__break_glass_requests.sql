-- ──────────────────────────────────────────────────────────────────────────────
-- Phase 7.4 — break_glass_requests table.
-- Records each emergency-access elevation: requestor, scope, approval state,
-- validity window. Audit rows for individual accesses inside the window go to
-- audit_events.action = BREAK_GLASS_ACCESS.
-- ──────────────────────────────────────────────────────────────────────────────

BEGIN;

CREATE TABLE IF NOT EXISTS break_glass_requests (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  requester_id text NOT NULL REFERENCES users(id),
  scope_type text NOT NULL,                  -- MEETING / DOCUMENT / TENANT
  scope_id text NOT NULL,
  reason text NOT NULL,
  status text NOT NULL DEFAULT 'PENDING',    -- PENDING / APPROVED / REJECTED / EXPIRED / REVOKED
  valid_from timestamptz,
  valid_until timestamptz,
  approver_id text REFERENCES users(id),
  approved_at timestamptz,
  rejected_at timestamptz,
  reject_reason text,
  revoked_at timestamptz,
  revoked_by text REFERENCES users(id),
  approvers_json jsonb NOT NULL DEFAULT '[]'::jsonb,  -- reserved for future N-of-M
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS break_glass_requests_user_idx
  ON break_glass_requests (tenant_id, requester_id, status, valid_until);

CREATE INDEX IF NOT EXISTS break_glass_requests_scope_idx
  ON break_glass_requests (tenant_id, scope_type, scope_id, status);

ALTER TABLE break_glass_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE break_glass_requests FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON break_glass_requests;
CREATE POLICY tenant_isolation ON break_glass_requests
  USING (tenant_id = public.current_tenant_id())
  WITH CHECK (tenant_id = public.current_tenant_id());

DROP TRIGGER IF EXISTS set_updated_at ON break_glass_requests;
CREATE TRIGGER set_updated_at BEFORE UPDATE ON break_glass_requests
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

COMMIT;
