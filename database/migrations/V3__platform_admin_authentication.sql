-- Platform identities remain in the control-plane database.
CREATE TABLE iam.platform_admin (
 id UUID PRIMARY KEY,
 username VARCHAR(80) NOT NULL UNIQUE,
 password_hash VARCHAR(256) NOT NULL,
 mfa_secret_ciphertext TEXT,
 mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_login_at TIMESTAMPTZ,
 disabled_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_single_platform_admin ON iam.platform_admin ((TRUE));
CREATE TABLE iam.platform_admin_setup_token (
 id UUID PRIMARY KEY,
 token_hash CHAR(64) NOT NULL UNIQUE,
 expires_at TIMESTAMPTZ NOT NULL,
 consumed_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_platform_admin_setup_token_expiry ON iam.platform_admin_setup_token (expires_at) WHERE consumed_at IS NULL;
CREATE TABLE iam.platform_admin_session (
 id UUID PRIMARY KEY,
 admin_id UUID NOT NULL REFERENCES iam.platform_admin(id) ON DELETE CASCADE,
 token_hash CHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 expires_at TIMESTAMPTZ NOT NULL,
 last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 revoked_at TIMESTAMPTZ,
 user_agent VARCHAR(512),
 ip_address INET
);
CREATE INDEX idx_platform_admin_session_admin ON iam.platform_admin_session (admin_id, expires_at DESC) WHERE revoked_at IS NULL;
CREATE INDEX idx_platform_admin_session_expiry ON iam.platform_admin_session (expires_at) WHERE revoked_at IS NULL;
CREATE TABLE iam.platform_login_attempt (
 key_hash CHAR(64) PRIMARY KEY,
 failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
 window_started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 locked_until TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE audit.platform_security_event (
 id UUID PRIMARY KEY,
 admin_id UUID,
 event_type VARCHAR(80) NOT NULL,
 outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('SUCCESS','FAILURE','DENIED')),
 source_ip INET,
 details JSONB NOT NULL DEFAULT '{}'::jsonb,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_platform_security_event_time ON audit.platform_security_event (occurred_at DESC);
CREATE INDEX idx_platform_security_event_admin ON audit.platform_security_event (admin_id, occurred_at DESC);
