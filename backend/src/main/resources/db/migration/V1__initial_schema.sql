CREATE TABLE organizations (
    id uuid PRIMARY KEY,
    name varchar(160) NOT NULL,
    code varchar(64) NOT NULL UNIQUE,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organization_settings (
    organization_id uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    default_reminder_time time NOT NULL DEFAULT '09:00',
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_accounts (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    display_name varchar(120) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    password_change_required boolean NOT NULL DEFAULT true,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_accounts_username UNIQUE (username)
);

CREATE TABLE memberships (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership_org_user UNIQUE (organization_id, user_id)
);

CREATE TABLE teams (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_org_name UNIQUE (organization_id, name)
);

CREATE TABLE team_members (
    team_id uuid NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    is_manager boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, membership_id)
);

CREATE TABLE roles (
    id uuid PRIMARY KEY,
    organization_id uuid REFERENCES organizations(id) ON DELETE CASCADE,
    code varchar(64) NOT NULL,
    name varchar(100) NOT NULL,
    is_system boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_roles_global_code ON roles (code) WHERE organization_id IS NULL;
CREATE UNIQUE INDEX uq_roles_org_code ON roles (organization_id, code) WHERE organization_id IS NOT NULL;

CREATE TABLE permissions (
    id uuid PRIMARY KEY,
    code varchar(100) NOT NULL UNIQUE,
    description varchar(255) NOT NULL
);

CREATE TABLE role_permissions (
    role_id uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE membership_roles (
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (membership_id, role_id)
);

CREATE TABLE dictionary_items (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    type varchar(40) NOT NULL CHECK (type IN ('SOURCE_CHANNEL', 'FOCUS_DIRECTION', 'INTERACTION_CHANNEL')),
    code varchar(80) NOT NULL,
    label varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dictionary_org_type_code UNIQUE (organization_id, type, code)
);

CREATE TABLE tags (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL,
    category varchar(60) NOT NULL,
    color varchar(16) NOT NULL,
    is_system boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_by_membership_id uuid REFERENCES memberships(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tag_org_name UNIQUE (organization_id, name)
);

CREATE TABLE customers (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    owner_membership_id uuid NOT NULL REFERENCES memberships(id),
    team_id uuid REFERENCES teams(id),
    visibility varchar(24) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'TEAM', 'ORGANIZATION')),
    full_name varchar(160) NOT NULL,
    nickname varchar(160),
    phone varchar(80),
    normalized_phone varchar(80),
    wechat_id varchar(120),
    normalized_wechat_id varchar(120),
    qq_id varchar(120),
    normalized_qq_id varchar(120),
    school varchar(200),
    major varchar(160),
    grade varchar(80),
    city varchar(120),
    identity_type varchar(40) NOT NULL CHECK (identity_type IN ('新生', '转专业', '在校生', '毕业生', '家长代问', '其他')),
    demand_summary text,
    intention_level varchar(12) NOT NULL CHECK (intention_level IN ('低', '中', '高')),
    urgency_level varchar(12) NOT NULL CHECK (urgency_level IN ('低', '中', '高')),
    verification_status varchar(24) NOT NULL CHECK (verification_status IN ('未核实', '已核实', '部分待确认')),
    source_dictionary_id uuid REFERENCES dictionary_items(id),
    source_channel_legacy varchar(160),
    current_status varchar(24) NOT NULL CHECK (current_status IN ('初次接触', '待核实', '已沟通', '跟进中', '高意向', '待决定', '已转化', '暂缓', '无效', '归档')),
    owner_notes text,
    last_contact_at timestamptz,
    next_follow_up_at timestamptz,
    archived_at timestamptz,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_customer_contact CHECK (
        nullif(trim(coalesce(phone, '')), '') IS NOT NULL OR
        nullif(trim(coalesce(wechat_id, '')), '') IS NOT NULL OR
        nullif(trim(coalesce(qq_id, '')), '') IS NOT NULL
    )
);
CREATE INDEX idx_customer_org_updated ON customers (organization_id, updated_at DESC);
CREATE INDEX idx_customer_owner ON customers (organization_id, owner_membership_id);
CREATE INDEX idx_customer_team ON customers (organization_id, team_id);
CREATE INDEX idx_customer_phone ON customers (organization_id, normalized_phone) WHERE normalized_phone IS NOT NULL;
CREATE INDEX idx_customer_wechat ON customers (organization_id, normalized_wechat_id) WHERE normalized_wechat_id IS NOT NULL;
CREATE INDEX idx_customer_qq ON customers (organization_id, normalized_qq_id) WHERE normalized_qq_id IS NOT NULL;
CREATE INDEX idx_customer_follow_up ON customers (organization_id, next_follow_up_at) WHERE archived_at IS NULL;

CREATE TABLE customer_focus_directions (
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    direction varchar(80) NOT NULL,
    PRIMARY KEY (customer_id, direction)
);

CREATE TABLE customer_tags (
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    tag_id uuid NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (customer_id, tag_id)
);

CREATE TABLE customer_access_grants (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    grantee_membership_id uuid REFERENCES memberships(id) ON DELETE CASCADE,
    grantee_team_id uuid REFERENCES teams(id) ON DELETE CASCADE,
    can_edit boolean NOT NULL DEFAULT false,
    granted_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_access_grantee CHECK (
        (grantee_membership_id IS NOT NULL AND grantee_team_id IS NULL) OR
        (grantee_membership_id IS NULL AND grantee_team_id IS NOT NULL)
    )
);
CREATE INDEX idx_access_customer ON customer_access_grants (customer_id);
CREATE INDEX idx_access_membership ON customer_access_grants (organization_id, grantee_membership_id);
CREATE INDEX idx_access_team ON customer_access_grants (organization_id, grantee_team_id);

CREATE TABLE interactions (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    contacted_at timestamptz NOT NULL,
    channel varchar(80) NOT NULL,
    summary varchar(500) NOT NULL,
    details text,
    outcome text,
    verification_todo text,
    next_follow_up_at timestamptz,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_interactions_customer_time ON interactions (customer_id, contacted_at DESC);

CREATE TABLE reminders (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    interaction_id uuid REFERENCES interactions(id) ON DELETE SET NULL,
    assigned_membership_id uuid NOT NULL REFERENCES memberships(id),
    remind_at timestamptz NOT NULL,
    title varchar(240) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED')),
    delivery_status varchar(24) NOT NULL DEFAULT 'UNSENT' CHECK (delivery_status IN ('UNSENT', 'SENT', 'FAILED')),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_reminders_assignee_time ON reminders (organization_id, assigned_membership_id, remind_at) WHERE status = 'PENDING';
CREATE INDEX idx_reminders_customer ON reminders (customer_id, remind_at DESC);

CREATE TABLE saved_filters (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    owner_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    filters jsonb NOT NULL,
    sort_by varchar(40) NOT NULL,
    show_on_homepage boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_filter_owner_name UNIQUE (owner_membership_id, name)
);

CREATE TABLE import_jobs (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    filename varchar(255) NOT NULL,
    format varchar(20) NOT NULL CHECK (format IN ('CSV', 'XLSX')),
    status varchar(24) NOT NULL CHECK (status IN ('PREVIEWED', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    total_rows integer NOT NULL DEFAULT 0,
    created_count integer NOT NULL DEFAULT 0,
    skipped_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    report jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE TABLE import_row_results (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    row_number integer NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('READY', 'CREATED', 'SKIPPED', 'FAILED')),
    normalized_data jsonb NOT NULL,
    messages jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_customer_id uuid REFERENCES customers(id),
    CONSTRAINT uq_import_job_row UNIQUE (import_job_id, row_number)
);

CREATE TABLE migration_runs (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source_key varchar(160) NOT NULL,
    source_sha256 varchar(64) NOT NULL,
    dry_run boolean NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE TABLE legacy_id_mappings (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source_key varchar(160) NOT NULL,
    source_table varchar(100) NOT NULL,
    legacy_id varchar(100) NOT NULL,
    target_id uuid NOT NULL,
    source_row_hash varchar(64) NOT NULL,
    target_version bigint,
    raw_times jsonb NOT NULL DEFAULT '{}'::jsonb,
    migrated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_legacy_mapping UNIQUE (organization_id, source_key, source_table, legacy_id)
);

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY,
    organization_id uuid REFERENCES organizations(id) ON DELETE SET NULL,
    actor_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    action varchar(100) NOT NULL,
    entity_type varchar(80),
    entity_id varchar(100),
    outcome varchar(24) NOT NULL DEFAULT 'SUCCESS',
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    trace_id varchar(64),
    ip_address varchar(64),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_org_time ON audit_logs (organization_id, created_at DESC);

CREATE TABLE assistant_runs (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    actor_membership_id uuid NOT NULL REFERENCES memberships(id),
    customer_id uuid REFERENCES customers(id) ON DELETE SET NULL,
    purpose varchar(80) NOT NULL,
    provider varchar(80) NOT NULL,
    model varchar(120),
    status varchar(24) NOT NULL,
    input_tokens integer,
    output_tokens integer,
    latency_ms bigint,
    error_code varchar(100),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE spring_session (
    primary_id char(36) NOT NULL,
    session_id char(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session(primary_id) ON DELETE CASCADE
);

