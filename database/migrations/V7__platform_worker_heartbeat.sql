CREATE TABLE platform.worker_instance (
    worker_id VARCHAR(120) PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_worker_instance_last_seen ON platform.worker_instance (last_seen_at DESC);
