-- 通用 Document 变更审计；审计记录与单据写入处于同一数据库事务。
CREATE TABLE audit.document_event (
    id UUID PRIMARY KEY,
    actor_admin_id UUID REFERENCES iam.tenant_admin(id),
    actor_user_id UUID REFERENCES iam.user_account(id),
    doctype VARCHAR(120) NOT NULL,
    document_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL CHECK (action IN ('CREATE','UPDATE','SUBMIT','CANCEL','VOID')),
    before_data JSONB,
    after_data JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((actor_admin_id IS NOT NULL) <> (actor_user_id IS NOT NULL))
);
CREATE INDEX ix_document_event_lookup ON audit.document_event(doctype,document_id,occurred_at DESC);
