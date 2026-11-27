-- 开发期 Flyway 与客户运行期动态 Schema Migration 共用的迁移治理记录。
CREATE TABLE IF NOT EXISTS meta.schema_migration (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL CHECK (source IN ('FLYWAY','RUNTIME')),
    version VARCHAR(128) NOT NULL,
    doctype VARCHAR(120) NOT NULL DEFAULT '',
    schema_name VARCHAR(63),
    table_name VARCHAR(63),
    checksum VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    plan_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb,
    actor_id UUID,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    UNIQUE (source,version,doctype)
);
CREATE INDEX IF NOT EXISTS ix_schema_migration_status
    ON meta.schema_migration(source,status,started_at DESC);
CREATE INDEX IF NOT EXISTS ix_schema_migration_target
    ON meta.schema_migration(schema_name,table_name,started_at DESC);
