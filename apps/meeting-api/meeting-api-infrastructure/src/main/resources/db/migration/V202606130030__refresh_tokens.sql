CREATE TABLE refresh_tokens (
    token_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_tenant ON refresh_tokens(tenant_id);

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;

CREATE POLICY refresh_tokens_tenant_isolation ON refresh_tokens
    USING (tenant_id = current_setting('app.tenant_id', true));

CREATE POLICY refresh_tokens_tenant_insert ON refresh_tokens
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
