-- 租户管理员会话、登录限流和安全审计；每个租户数据库独立执行。
ALTER TABLE iam.tenant_admin
    ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

CREATE TABLE iam.tenant_admin_session (
    id UUID PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES iam.tenant_admin(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    user_agent VARCHAR(512),
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_tenant_admin_session_active
    ON iam.tenant_admin_session(admin_id, expires_at DESC)
    WHERE revoked_at IS NULL;

CREATE TABLE iam.tenant_login_attempt (
    key_hash CHAR(64) PRIMARY KEY,
    failed_count INTEGER NOT NULL DEFAULT 0,
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE audit.tenant_security_event (
    id UUID PRIMARY KEY,
    tenant_admin_id UUID REFERENCES iam.tenant_admin(id),
    event_type VARCHAR(48) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    source_ip INET,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_tenant_security_event_time
    ON audit.tenant_security_event(occurred_at DESC);
