-- Outbox 保存与业务事务原子提交的事件；background_task 管理可重试的执行状态。
CREATE TABLE IF NOT EXISTS platform.outbox_event (
    id UUID PRIMARY KEY,
    event_type VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    aggregate_type VARCHAR(160),
    aggregate_id VARCHAR(256),
    idempotency_key VARCHAR(256) NOT NULL,
    correlation_id VARCHAR(256),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_outbox_event_idempotency UNIQUE (idempotency_key)
);

CREATE TABLE IF NOT EXISTS platform.background_task (
    id UUID PRIMARY KEY,
    outbox_event_id UUID NOT NULL UNIQUE REFERENCES platform.outbox_event(id) ON DELETE RESTRICT,
    task_type VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 8 CHECK (max_attempts > 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_owner VARCHAR(256),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(2000),
    requested_by VARCHAR(256),
    replayed_by VARCHAR(256),
    replayed_at TIMESTAMPTZ,
    replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_background_task_idempotency UNIQUE (task_type, idempotency_key),
    CONSTRAINT ck_background_task_lease CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_background_task_ready
    ON platform.background_task (available_at, created_at)
    WHERE status IN ('PENDING', 'RETRY_WAIT');
CREATE INDEX IF NOT EXISTS idx_background_task_expired_lease
    ON platform.background_task (lease_until)
    WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_background_task_status_created
    ON platform.background_task (status, created_at DESC);

CREATE TABLE IF NOT EXISTS platform.processed_task (
    task_type VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (task_type, idempotency_key)
);

CREATE OR REPLACE VIEW platform.background_task_metrics AS
SELECT
    count(*) FILTER (WHERE status = 'PENDING') AS pending_count,
    count(*) FILTER (WHERE status = 'RETRY_WAIT') AS retry_wait_count,
    count(*) FILTER (WHERE status = 'RUNNING') AS running_count,
    count(*) FILTER (WHERE status = 'DEAD') AS dead_count,
    count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded_count,
    count(*) FILTER (WHERE status = 'RUNNING' AND lease_until <= clock_timestamp()) AS expired_lease_count,
    COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(created_at)
        FILTER (WHERE status IN ('PENDING', 'RETRY_WAIT'))), 0) AS oldest_pending_age_seconds
FROM platform.background_task;
