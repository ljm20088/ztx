-- 租户级元数据定义及可追溯草稿历史；租户数据库不能写入平台范围。
CREATE TABLE meta.document_definition (
    id UUID PRIMARY KEY,
    scope VARCHAR(16) NOT NULL DEFAULT 'TENANT' CHECK (scope IN ('PLATFORM', 'TENANT')),
    doctype VARCHAR(120) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'VALIDATED', 'RETIRED')),
    definition_json JSONB NOT NULL,
    checksum CHAR(64) NOT NULL,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (scope, doctype, version)
);

CREATE INDEX ix_document_definition_latest
    ON meta.document_definition (scope, doctype, version DESC);

CREATE TABLE meta.document_definition_history (
    id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES meta.document_definition(id) ON DELETE RESTRICT,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('PLATFORM', 'TENANT')),
    doctype VARCHAR(120) NOT NULL,
    version INTEGER NOT NULL,
    action VARCHAR(24) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'COPY', 'VALIDATE', 'VALIDATION_FAILED')),
    definition_json JSONB NOT NULL,
    diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb,
    actor_id UUID NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_document_definition_history_lookup
    ON meta.document_definition_history (scope, doctype, version, changed_at DESC);
