ALTER TABLE customer_deal_requests DROP CONSTRAINT customer_deal_request_status_check;
ALTER TABLE customer_deal_requests ADD CONSTRAINT customer_deal_request_status_check
    CHECK (status IN ('PENDING','APPROVED','REJECTED','REVERSED'));

ALTER TABLE customer_deal_requests DROP CONSTRAINT customer_deal_request_source_check;
ALTER TABLE customer_deal_requests ADD CONSTRAINT customer_deal_request_source_check
    CHECK (source IN ('DIRECT','LEGACY_BUSINESS_CYCLE','FEISHU_BOT'));

ALTER TABLE customer_deal_requests
    ADD COLUMN confirmation_channel varchar(24),
    ADD COLUMN confirmed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    ADD COLUMN confirmed_at timestamptz,
    ADD COLUMN bonus_total_cents bigint,
    ADD COLUMN attribution_state varchar(24) NOT NULL DEFAULT 'NEEDS_BACKFILL',
    ADD COLUMN reversed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    ADD COLUMN reversed_at timestamptz,
    ADD COLUMN reversal_reason varchar(500),
    ADD CONSTRAINT customer_deal_confirmation_channel_check CHECK (
        confirmation_channel IS NULL OR confirmation_channel IN ('WEB_ADMIN','ANDROID_ADMIN','FEISHU_BOT','LEGACY')
    ),
    ADD CONSTRAINT customer_deal_attribution_state_check CHECK (
        attribution_state IN ('COMPLETE','NEEDS_BACKFILL')
    ),
    ADD CONSTRAINT customer_deal_bonus_total_check CHECK (
        bonus_total_cents IS NULL OR bonus_total_cents >= 0
    );

UPDATE customer_deal_requests
SET confirmation_channel = CASE WHEN source='LEGACY_BUSINESS_CYCLE' THEN 'LEGACY' ELSE 'WEB_ADMIN' END,
    confirmed_by_membership_id = decided_by_membership_id,
    confirmed_at = decided_at
WHERE status='APPROVED';

DROP INDEX uq_customer_deal_request_approved;
CREATE UNIQUE INDEX uq_customer_deal_request_approved
    ON customer_deal_requests (organization_id, customer_id) WHERE status='APPROVED';

CREATE TABLE customer_deal_participants (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    request_id uuid NOT NULL REFERENCES customer_deal_requests(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    participant_role varchar(16) NOT NULL,
    display_name_snapshot varchar(160) NOT NULL,
    team_name_snapshot varchar(160),
    bonus_cents bigint NOT NULL DEFAULT 0,
    position_index integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_deal_participant_role_check CHECK (participant_role IN ('PRIMARY','ASSISTANT')),
    CONSTRAINT customer_deal_participant_bonus_check CHECK (bonus_cents >= 0),
    CONSTRAINT customer_deal_participant_position_check CHECK (position_index BETWEEN 0 AND 2),
    CONSTRAINT customer_deal_participant_unique UNIQUE (request_id, membership_id)
);
CREATE UNIQUE INDEX uq_customer_deal_primary
    ON customer_deal_participants(request_id) WHERE participant_role='PRIMARY';
CREATE INDEX customer_deal_participant_leaderboard_idx
    ON customer_deal_participants(organization_id, participant_role, membership_id, request_id);

CREATE TABLE customer_deal_bonus_ledger (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    request_id uuid NOT NULL REFERENCES customer_deal_requests(id) ON DELETE CASCADE,
    participant_id uuid NOT NULL REFERENCES customer_deal_participants(id),
    membership_id uuid NOT NULL REFERENCES memberships(id),
    entry_type varchar(16) NOT NULL,
    amount_cents bigint NOT NULL,
    effective_at timestamptz NOT NULL,
    reversed_entry_id uuid REFERENCES customer_deal_bonus_ledger(id),
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_deal_bonus_entry_type_check CHECK (entry_type IN ('CREDIT','REVERSAL')),
    CONSTRAINT customer_deal_bonus_sign_check CHECK (
        (entry_type='CREDIT' AND amount_cents >= 0) OR (entry_type='REVERSAL' AND amount_cents <= 0)
    )
);
CREATE UNIQUE INDEX uq_customer_deal_bonus_credit
    ON customer_deal_bonus_ledger(participant_id) WHERE entry_type='CREDIT';
CREATE UNIQUE INDEX uq_customer_deal_bonus_reversal
    ON customer_deal_bonus_ledger(reversed_entry_id) WHERE entry_type='REVERSAL';
CREATE INDEX customer_deal_bonus_org_time_idx
    ON customer_deal_bonus_ledger(organization_id, effective_at DESC);

CREATE OR REPLACE FUNCTION reject_customer_deal_financial_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'customer deal attribution and bonus history is append-only';
END;
$$;
CREATE TRIGGER customer_deal_participants_append_only
BEFORE UPDATE OR DELETE ON customer_deal_participants
FOR EACH ROW EXECUTE FUNCTION reject_customer_deal_financial_history_mutation();
CREATE TRIGGER customer_deal_bonus_ledger_append_only
BEFORE UPDATE OR DELETE ON customer_deal_bonus_ledger
FOR EACH ROW EXECUTE FUNCTION reject_customer_deal_financial_history_mutation();

CREATE TABLE feishu_deal_bot_settings (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL UNIQUE REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT false,
    submission_chat_id varchar(128),
    submission_chat_name varchar(160),
    broadcast_chat_id varchar(128),
    broadcast_chat_name varchar(160),
    bot_open_id varchar(128),
    bot_name varchar(160),
    default_bonus_cents bigint NOT NULL DEFAULT 0,
    broadcast_title varchar(300) NOT NULL DEFAULT '🎉 英格科技成单播报 🎉',
    leaderboard_title varchar(300) NOT NULL DEFAULT '🌹英格科技我为你操碎了心🌹',
    leaderboard_footer varchar(500) NOT NULL DEFAULT '👀看看又是哪个小笨蛋饿死了👀\n🔨每日任务：激活 登记 对接🔨',
    privacy_acknowledged_at timestamptz,
    updated_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_deal_default_bonus_check CHECK (default_bonus_cents >= 0),
    CONSTRAINT feishu_deal_chat_distinct_check CHECK (
        submission_chat_id IS NULL OR broadcast_chat_id IS NULL OR submission_chat_id <> broadcast_chat_id
    )
);

CREATE TABLE feishu_deal_bot_event_inbox (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    event_id varchar(180) NOT NULL,
    message_id varchar(180),
    event_type varchar(160) NOT NULL,
    chat_id varchar(128),
    sender_feishu_user_id varchar(128),
    status varchar(24) NOT NULL DEFAULT 'RECEIVED',
    error_code varchar(120),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    CONSTRAINT feishu_deal_event_unique UNIQUE (integration_id,event_id),
    CONSTRAINT feishu_deal_event_status_check CHECK (
        status IN ('RECEIVED','PROCESSING','APPLIED','IGNORED','REJECTED','FAILED')
    )
);

CREATE TABLE feishu_deal_bot_sessions (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    chat_id varchar(128) NOT NULL,
    feishu_user_id varchar(128) NOT NULL,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    customer_id uuid REFERENCES customers(id) ON DELETE CASCADE,
    primary_membership_id uuid REFERENCES memberships(id),
    assistant_membership_ids uuid[] NOT NULL DEFAULT '{}',
    bonus_total_cents bigint NOT NULL DEFAULT 0,
    allocations jsonb NOT NULL DEFAULT '{}'::jsonb,
    search_query varchar(200),
    search_page integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'SEARCHING',
    edit_count integer NOT NULL DEFAULT 0,
    last_message_id varchar(180),
    expires_at timestamptz NOT NULL,
    confirmed_request_id uuid REFERENCES customer_deal_requests(id),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_deal_session_status_check CHECK (
        status IN ('SEARCHING','SELECTED','DRAFT','CONFIRMED','CANCELLED','EXPIRED')
    ),
    CONSTRAINT feishu_deal_session_edit_count_check CHECK (edit_count BETWEEN 0 AND 3),
    CONSTRAINT feishu_deal_session_bonus_check CHECK (bonus_total_cents >= 0)
);
CREATE UNIQUE INDEX uq_feishu_deal_active_session
    ON feishu_deal_bot_sessions(integration_id,chat_id,membership_id)
    WHERE status IN ('SEARCHING','SELECTED','DRAFT');
CREATE INDEX feishu_deal_session_expiry_idx
    ON feishu_deal_bot_sessions(status,expires_at);

CREATE TABLE feishu_deal_message_outbox (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    chat_id varchar(128) NOT NULL,
    message_type varchar(24) NOT NULL,
    payload jsonb NOT NULL,
    message_uuid uuid NOT NULL,
    purpose varchar(32) NOT NULL,
    aggregate_id uuid,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    remote_message_id varchar(180),
    last_error_code varchar(120),
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_deal_outbox_unique UNIQUE (integration_id,message_uuid),
    CONSTRAINT feishu_deal_outbox_status_check CHECK (
        status IN ('PENDING','SENDING','RETRYING','SENT','FAILED')
    )
);
CREATE INDEX feishu_deal_outbox_ready_idx
    ON feishu_deal_message_outbox(status,next_attempt_at,created_at);

CREATE TABLE feishu_deal_leaderboard_runs (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    leaderboard_date date NOT NULL,
    message_outbox_id uuid REFERENCES feishu_deal_message_outbox(id),
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT feishu_deal_leaderboard_unique UNIQUE (integration_id,leaderboard_date),
    CONSTRAINT feishu_deal_leaderboard_status_check CHECK (status IN ('PENDING','SENT','FAILED'))
);

UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id,version,title,summary,sections,released_at,published,current
) VALUES (
    '20000000-0000-0000-0000-000000000030',
    '2026.09.06.1',
    '飞书成单机器人与每日榜单',
    '新增飞书群内客户查询、成单确认、奖金台账、成单广播和每日22点榜单。',
    '{"新增":["成单提交群机器人支持客户搜索、人员与奖金编辑及员工确认","成单确认后自动发布全员广播","每日22点发布自然月开单榜单","新增奖金追加式台账、历史待补录和管理员冲正"],"改进":["飞书确认可安全复用已有网页或Android待审核申请","群绑定使用chat_id，改群名不影响运行"],"安全":["只有已绑定、已启用且具备客户维护权限的员工可操作","搜索结果不显示完整联系方式，奖金不进入绩效积分账本","广播客户姓名需组织管理员显式确认"]}'::jsonb,
    '2026-09-06T12:00:00+08:00',true,true
);
