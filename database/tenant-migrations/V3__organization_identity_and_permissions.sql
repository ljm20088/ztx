-- V3：企业账套、组织、人事任职、岗位授权与集团基础档案。
CREATE SCHEMA IF NOT EXISTS org;
CREATE SCHEMA IF NOT EXISTS hr;

ALTER TABLE iam.tenant_admin
    ADD COLUMN IF NOT EXISTS is_group_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE org.company (
    id UUID PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, name VARCHAR(160) NOT NULL,
    legal_name VARCHAR(200), tax_number VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE org.book (
    id UUID PRIMARY KEY, company_id UUID NOT NULL REFERENCES org.company(id),
    code VARCHAR(40) NOT NULL, name VARCHAR(160) NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(company_id,code)
);
CREATE INDEX idx_org_book_company ON org.book(company_id,status);

CREATE TABLE org.organization_unit (
    id UUID PRIMARY KEY, company_id UUID REFERENCES org.company(id),
    parent_id UUID REFERENCES org.organization_unit(id), code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    unit_type VARCHAR(24) NOT NULL CHECK(unit_type IN ('BRANCH','DIVISION','DEPARTMENT','FACTORY','WORKSHOP','TEAM','OTHER')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(company_id,code), CHECK(parent_id IS NULL OR parent_id<>id)
);
CREATE INDEX idx_org_unit_parent ON org.organization_unit(parent_id,status);

CREATE TABLE hr.employee (
    id UUID PRIMARY KEY, employee_no VARCHAR(40) NOT NULL UNIQUE, name VARCHAR(120) NOT NULL,
    mobile VARCHAR(32), email VARCHAR(160),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE iam.user_account (
    id UUID PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, password_hash VARCHAR(256),
    display_name VARCHAR(120) NOT NULL, is_group_admin BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','LOCKED','DISABLED')),
    last_login_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE iam.user_session (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES iam.user_account(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
    user_agent VARCHAR(512), ip_address INET, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_tenant_user_session_active ON iam.user_session(user_id,expires_at DESC) WHERE revoked_at IS NULL;

CREATE TABLE hr.employment (
    id UUID PRIMARY KEY, employee_id UUID NOT NULL REFERENCES hr.employee(id),
    user_id UUID REFERENCES iam.user_account(id), company_id UUID NOT NULL REFERENCES org.company(id),
    book_id UUID REFERENCES org.book(id), organization_unit_id UUID REFERENCES org.organization_unit(id),
    employment_type VARCHAR(24) NOT NULL DEFAULT 'EMPLOYEE', starts_on DATE NOT NULL, ends_on DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ENDED','SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK(ends_on IS NULL OR ends_on>=starts_on)
);
CREATE INDEX idx_hr_employment_employee ON hr.employment(employee_id,starts_on DESC);
CREATE INDEX idx_hr_employment_user ON hr.employment(user_id,status);
CREATE INDEX idx_hr_employment_scope ON hr.employment(company_id,book_id,organization_unit_id,status);

CREATE TABLE iam.job_position (
    id UUID PRIMARY KEY, company_id UUID REFERENCES org.company(id),
    book_id UUID REFERENCES org.book(id), organization_unit_id UUID REFERENCES org.organization_unit(id),
    code VARCHAR(40) NOT NULL, name VARCHAR(120) NOT NULL, description VARCHAR(500),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(company_id,code), CHECK((book_id IS NULL AND organization_unit_id IS NULL) OR company_id IS NOT NULL)
);
CREATE TABLE iam.module_role (
    id UUID PRIMARY KEY, module_key VARCHAR(64) NOT NULL, code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL, description VARCHAR(500),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(module_key,code)
);
CREATE TABLE iam.job_position_module_role (
    position_id UUID NOT NULL REFERENCES iam.job_position(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES iam.module_role(id) ON DELETE CASCADE,
    PRIMARY KEY(position_id,role_id)
);
CREATE TABLE hr.employment_position (
    employment_id UUID NOT NULL REFERENCES hr.employment(id) ON DELETE CASCADE,
    position_id UUID NOT NULL REFERENCES iam.job_position(id), PRIMARY KEY(employment_id,position_id)
);
CREATE TABLE iam.role_permission (
    id UUID PRIMARY KEY, role_id UUID NOT NULL REFERENCES iam.module_role(id) ON DELETE CASCADE,
    permission_key VARCHAR(160) NOT NULL, effect VARCHAR(8) NOT NULL CHECK(effect IN ('ALLOW','DENY')),
    data_scope VARCHAR(16) NOT NULL DEFAULT 'SELF' CHECK(data_scope IN ('SELF','UNIT','SUBTREE','BOOK','COMPANY','TENANT','ALL')),
    field_permissions JSONB NOT NULL DEFAULT '{}'::jsonb, UNIQUE(role_id,permission_key)
);

CREATE TABLE org.group_master_item (
    id UUID PRIMARY KEY, category VARCHAR(64) NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(160) NOT NULL,
    master_data JSONB NOT NULL DEFAULT '{}'::jsonb, protected_fields TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    created_by UUID REFERENCES iam.tenant_admin(id), updated_by UUID REFERENCES iam.tenant_admin(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(category,code)
);
CREATE TABLE org.company_group_master (
    company_id UUID NOT NULL REFERENCES org.company(id), master_item_id UUID NOT NULL REFERENCES org.group_master_item(id),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
    local_data JSONB NOT NULL DEFAULT '{}'::jsonb, enabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(company_id,master_item_id)
);
CREATE TABLE org.group_master_request (
    id UUID PRIMARY KEY, company_id UUID NOT NULL REFERENCES org.company(id),
    master_item_id UUID NOT NULL REFERENCES org.group_master_item(id),
    requested_by UUID REFERENCES iam.tenant_admin(id),
    requested_by_user_id UUID REFERENCES iam.user_account(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reviewed_by UUID REFERENCES iam.tenant_admin(id), reviewed_at TIMESTAMPTZ,
    UNIQUE(company_id,master_item_id),
    CHECK ((requested_by IS NOT NULL) <> (requested_by_user_id IS NOT NULL))
);

CREATE TABLE audit.organization_event (
    id UUID PRIMARY KEY, actor_admin_id UUID REFERENCES iam.tenant_admin(id),
    actor_user_id UUID REFERENCES iam.user_account(id),
    entity_type VARCHAR(48) NOT NULL, entity_id UUID, action VARCHAR(48) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb, occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((actor_admin_id IS NOT NULL) <> (actor_user_id IS NOT NULL))
);
CREATE INDEX idx_organization_event_time ON audit.organization_event(occurred_at DESC);
ALTER TABLE audit.tenant_security_event ADD COLUMN IF NOT EXISTS tenant_user_id UUID REFERENCES iam.user_account(id);
CREATE INDEX idx_group_master_reference ON org.company_group_master(master_item_id,status);

-- 任职范围必须属于同一企业，岗位企业范围不能越过任职企业。
CREATE FUNCTION org.validate_employment_scope() RETURNS trigger LANGUAGE plpgsql AS $
BEGIN
    IF NEW.book_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM org.book b WHERE b.id=NEW.book_id AND b.company_id=NEW.company_id
    ) THEN RAISE EXCEPTION '任职账套必须属于同一企业'; END IF;
    IF NEW.organization_unit_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM org.organization_unit u
        WHERE u.id=NEW.organization_unit_id AND (u.company_id IS NULL OR u.company_id=NEW.company_id)
    ) THEN RAISE EXCEPTION '任职组织必须属于同一企业'; END IF;
    RETURN NEW;
END $;
CREATE TRIGGER trg_employment_scope BEFORE INSERT OR UPDATE OF company_id,book_id,organization_unit_id
ON hr.employment FOR EACH ROW EXECUTE FUNCTION org.validate_employment_scope();

CREATE FUNCTION org.validate_position_scope() RETURNS trigger LANGUAGE plpgsql AS $
BEGIN
    IF NEW.book_id IS NOT NULL AND (NEW.company_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM org.book b WHERE b.id=NEW.book_id AND b.company_id=NEW.company_id
    )) THEN RAISE EXCEPTION '岗位账套必须属于同一企业'; END IF;
    IF NEW.organization_unit_id IS NOT NULL AND (NEW.company_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM org.organization_unit u
        WHERE u.id=NEW.organization_unit_id AND (u.company_id IS NULL OR u.company_id=NEW.company_id)
    )) THEN RAISE EXCEPTION '岗位组织必须属于同一企业'; END IF;
    RETURN NEW;
END $;
CREATE TRIGGER trg_position_scope BEFORE INSERT OR UPDATE OF company_id,book_id,organization_unit_id
ON iam.job_position FOR EACH ROW EXECUTE FUNCTION org.validate_position_scope();

CREATE FUNCTION org.validate_employment_position() RETURNS trigger LANGUAGE plpgsql AS $
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM hr.employment e JOIN iam.job_position p ON p.id=NEW.position_id
        WHERE e.id=NEW.employment_id
          AND (p.company_id IS NULL OR p.company_id=e.company_id)
          AND (e.book_id IS NULL OR p.book_id=e.book_id)
          AND (e.organization_unit_id IS NULL OR EXISTS (
              WITH RECURSIVE unit_scope(id,parent_id) AS (
                  SELECT id,parent_id FROM org.organization_unit WHERE id=e.organization_unit_id
                  UNION ALL
                  SELECT child.id,child.parent_id FROM org.organization_unit child
                  JOIN unit_scope parent ON child.parent_id=parent.id
              )
              SELECT 1 FROM unit_scope WHERE id=p.organization_unit_id
          ))
    ) THEN RAISE EXCEPTION '岗位范围不能超出任职企业、账套和组织范围'; END IF;
    RETURN NEW;
END $;
CREATE TRIGGER trg_employment_position BEFORE INSERT OR UPDATE ON hr.employment_position
FOR EACH ROW EXECUTE FUNCTION org.validate_employment_position();
