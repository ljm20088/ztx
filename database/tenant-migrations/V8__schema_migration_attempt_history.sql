-- 每次运行期动态 Schema Migration 的执行尝试独立留痕，避免重试覆盖之前的失败诊断。
CREATE TABLE IF NOT EXISTS meta.schema_migration_attempt (
    id UUID PRIMARY KEY,
    migration_id UUID NOT NULL REFERENCES meta.schema_migration(id),
    attempt_no INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    checksum VARCHAR(128) NOT NULL,
    plan_json JSONB NOT NULL,
    diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb,
    actor_id UUID,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    UNIQUE (migration_id,attempt_no)
);
CREATE INDEX IF NOT EXISTS ix_schema_migration_attempt_history
    ON meta.schema_migration_attempt(migration_id,attempt_no DESC);

-- 为升级前已有的运行期迁移保留当前可知状态；历史上已被覆盖的失败无法追溯恢复。
INSERT INTO meta.schema_migration_attempt
    (id,migration_id,attempt_no,status,checksum,plan_json,diagnostics,actor_id,started_at,completed_at,error_message)
SELECT gen_random_uuid(),id,1,status,checksum,plan_json,diagnostics,actor_id,started_at,completed_at,error_message
FROM meta.schema_migration
WHERE source='RUNTIME'
ON CONFLICT(migration_id,attempt_no) DO NOTHING;
