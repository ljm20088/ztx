-- 控制面保存租户注册、数据库路由、开通任务和生命周期审计。
CREATE TABLE platform.tenant (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROVISIONING', 'ACTIVE', 'INACTIVE', 'FAILED')),
    database_name VARCHAR(63) NOT NULL UNIQUE,
    last_error TEXT,
    provisioning_lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    provisioned_at TIMESTAMPTZ
);
CREATE INDEX idx_platform_tenant_status ON platform.tenant(status, updated_at DESC);
CREATE TABLE platform.tenant_provisioning_task (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_tenant_provisioning_task_tenant ON platform.tenant_provisioning_task(tenant_id, started_at DESC);
CREATE TABLE audit.tenant_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    actor_admin_id UUID NOT NULL REFERENCES iam.platform_admin(id),
    action VARCHAR(40) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_tenant_event_time ON audit.tenant_event(tenant_id, occurred_at DESC);
