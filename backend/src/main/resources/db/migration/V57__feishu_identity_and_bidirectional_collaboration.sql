ALTER TABLE feishu_bitable_integrations
    ADD COLUMN oauth_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN inbound_sync_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN tenant_key varchar(128),
    ADD COLUMN event_state varchar(24) NOT NULL DEFAULT 'DISABLED',
    ADD COLUMN last_event_at timestamptz,
    ADD COLUMN last_event_error_code varchar(120),
    ADD CONSTRAINT feishu_event_state_check CHECK (
        event_state IN ('DISABLED','CONNECTING','CONNECTED','DEGRADED','PAUSED')
    );

CREATE TABLE feishu_identity_bindings (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    user_account_id uuid NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    tenant_key varchar(128) NOT NULL,
    feishu_user_id varchar(128) NOT NULL,
    open_id varchar(128),
    union_id varchar(128),
    display_name varchar(160),
    active boolean NOT NULL DEFAULT true,
    bound_at timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    revoked_at timestamptz,
    revoked_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_binding_tenant_user_unique UNIQUE (tenant_key, feishu_user_id),
    CONSTRAINT feishu_binding_membership_unique UNIQUE (integration_id, membership_id)
);
CREATE INDEX feishu_identity_bindings_member_idx
    ON feishu_identity_bindings (membership_id, active);
CREATE INDEX feishu_identity_bindings_identity_idx
    ON feishu_identity_bindings (tenant_key, feishu_user_id, active);

CREATE TABLE feishu_oauth_intents (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    purpose varchar(16) NOT NULL,
    membership_id uuid REFERENCES memberships(id) ON DELETE CASCADE,
    state_hash varchar(64) NOT NULL UNIQUE,
    confirmation_hash varchar(64),
    return_to varchar(300) NOT NULL,
    tenant_key varchar(128),
    feishu_user_id varchar(128),
    open_id varchar(128),
    union_id varchar(128),
    display_name varchar(160),
    expires_at timestamptz NOT NULL,
    callback_completed_at timestamptz,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_oauth_intent_purpose_check CHECK (purpose IN ('LOGIN','BIND'))
);
CREATE INDEX feishu_oauth_intents_expiry_idx ON feishu_oauth_intents (expires_at, consumed_at);

CREATE TABLE feishu_event_inbox (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    event_id varchar(160) NOT NULL,
    event_type varchar(160) NOT NULL,
    tenant_key varchar(128),
    table_id varchar(128),
    record_id varchar(128),
    action_kind varchar(32),
    operator_user_id varchar(128),
    operator_open_id varchar(128),
    status varchar(24) NOT NULL DEFAULT 'RECEIVED',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error_code varchar(120),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    CONSTRAINT feishu_event_inbox_unique UNIQUE (integration_id, event_id, record_id, action_kind),
    CONSTRAINT feishu_event_inbox_status_check CHECK (
        status IN ('RECEIVED','PROCESSING','APPLIED','IGNORED','RESTORED','CONFLICT','REJECTED','FAILED')
    )
);
CREATE INDEX feishu_event_inbox_ready_idx
    ON feishu_event_inbox (integration_id, status, next_attempt_at, received_at);

CREATE TABLE feishu_inbound_changes (
    id uuid PRIMARY KEY,
    inbox_event_id uuid NOT NULL REFERENCES feishu_event_inbox(id) ON DELETE CASCADE,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid REFERENCES customers(id) ON DELETE SET NULL,
    record_id varchar(128) NOT NULL,
    actor_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    outcome varchar(24) NOT NULL,
    changed_fields text[] NOT NULL DEFAULT '{}',
    source_version bigint,
    reason_code varchar(120),
    before_hash varchar(64),
    after_hash varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_inbound_change_outcome_check CHECK (
        outcome IN ('APPLIED','IGNORED','RESTORED','CONFLICT','REJECTED','FAILED')
    )
);
CREATE INDEX feishu_inbound_changes_org_time_idx
    ON feishu_inbound_changes (organization_id, created_at DESC);

CREATE TABLE feishu_customer_drafts (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    record_id varchar(128) NOT NULL,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    status varchar(32) NOT NULL DEFAULT 'CONFIRM_REQUIRED',
    safe_payload jsonb NOT NULL,
    duplicate_result jsonb,
    created_customer_id uuid REFERENCES customers(id) ON DELETE SET NULL,
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT feishu_customer_draft_record_unique UNIQUE (integration_id, record_id),
    CONSTRAINT feishu_customer_draft_status_check CHECK (
        status IN ('CONFIRM_REQUIRED','DUPLICATE_BLOCKED','CREATED','CANCELLED','EXPIRED')
    )
);
CREATE INDEX feishu_customer_drafts_org_status_idx
    ON feishu_customer_drafts (organization_id, status, created_at DESC);

UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000028',
    '2026.09.03.3',
    '飞书账号绑定与安全双向协同',
    '新增飞书账号绑定和快捷登录，并为客户安全字段、沟通与任务建立受权限保护的飞书协同入口。',
    '{"新增":["新增飞书企业账号绑定、快捷登录、个人解绑和管理员撤销","新增客户安全字段入站事件、冲突队列、删除恢复和建档草稿","新增飞书工作台客户、沟通和任务受控操作接口"],"改进":["英格云继续作为客户归属、审核和审计的唯一可信数据源","飞书事件按事件 ID 去重，安全字段写入后立即回写最新版本和规范值","删除的客户行会重建并更新映射，飞书建档草稿确认后复用原记录","受控字段和越权修改会拒绝并恢复英格云值"],"安全":["绑定仅使用 tenant_key 与 user_id，不按姓名、手机号或用户名自动匹配","OAuth state 和确认票据一次性使用且五分钟失效，不保存飞书用户访问令牌","长连接运行组件默认启用，但组织入站开关默认关闭并保留紧急停机开关","正式快捷登录要求可信 HTTPS，内网 HTTP 仅用于受限联调"]}'::jsonb,
    '2026-09-03T14:00:00+08:00',
    true,
    true
);
