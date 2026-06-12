-- ──────────────────────────────────────────────────────────────────────────────
-- I6: Callback Nonce Deduplication Table
-- 防止重放攻击：5分钟 TTL 窗口内的 nonce 去重
-- ──────────────────────────────────────────────────────────────────────────────

BEGIN;

-- Nonce 去重表
CREATE TABLE IF NOT EXISTS callback_nonces (
  id text PRIMARY KEY DEFAULT gen_random_uuid()::text,
  tenant_id text NOT NULL REFERENCES tenants(id),
  nonce text NOT NULL,
  worker_id text NOT NULL,
  task_id text,
  step_name text,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT (now() + interval '5 minutes'),
  CONSTRAINT callback_nonces_uk UNIQUE (tenant_id, nonce)
);

-- 过期索引，用于清理
CREATE INDEX IF NOT EXISTS callback_nonces_expires_idx
  ON callback_nonces (expires_at)
  WHERE expires_at IS NOT NULL;

-- 租户索引
CREATE INDEX IF NOT EXISTS callback_nonces_tenant_idx
  ON callback_nonces (tenant_id, created_at);

-- RLS 策略
ALTER TABLE callback_nonces ENABLE ROW LEVEL SECURITY;
ALTER TABLE callback_nonces FORCE ROW LEVEL SECURITY;

CREATE POLICY callback_nonces_tenant_isolation ON callback_nonces
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

COMMENT ON TABLE callback_nonces IS 'Callback nonce deduplication table with 5-minute TTL window';
COMMENT ON COLUMN callback_nonces.nonce IS 'Unique nonce from callback signature';
COMMENT ON COLUMN callback_nonces.expires_at IS 'TTL expiration time, default 5 minutes after creation';

COMMIT;
