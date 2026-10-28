ALTER TABLE customer_deal_requests
    ADD COLUMN order_amount_cents bigint,
    ADD CONSTRAINT customer_deal_order_amount_check CHECK (
        order_amount_cents IS NULL OR order_amount_cents >= 0
    );

ALTER TABLE feishu_deal_bot_sessions
    ADD COLUMN order_amount_cents bigint NOT NULL DEFAULT 0,
    ADD COLUMN created_customer_id uuid REFERENCES customers(id) ON DELETE SET NULL,
    ADD COLUMN customer_created_in_session boolean NOT NULL DEFAULT false,
    ADD COLUMN draft_ready_at timestamptz,
    ADD COLUMN pending_customer_name varchar(160),
    ADD COLUMN pending_owner_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    ADD COLUMN pending_duplicate_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT feishu_deal_session_order_amount_check CHECK (order_amount_cents >= 0),
    ADD CONSTRAINT feishu_deal_pending_duplicate_count_check CHECK (pending_duplicate_count >= 0);

ALTER TABLE feishu_deal_bot_sessions DROP CONSTRAINT feishu_deal_session_status_check;
ALTER TABLE feishu_deal_bot_sessions ADD CONSTRAINT feishu_deal_session_status_check CHECK (
    status IN ('SEARCHING','NEW_PENDING','SELECTED','DRAFT','CONFIRMED','CANCELLED','EXPIRED')
);
DROP INDEX uq_feishu_deal_active_session;
CREATE UNIQUE INDEX uq_feishu_deal_active_session
    ON feishu_deal_bot_sessions(integration_id,chat_id,membership_id)
    WHERE status IN ('SEARCHING','NEW_PENDING','SELECTED','DRAFT');

ALTER TABLE feishu_deal_bot_settings
    ADD COLUMN quick_customer_creation_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN financial_visibility_acknowledged_at timestamptz;

-- The new workflow publishes order and bonus amounts across the organization. Existing
-- installations must explicitly accept that broader visibility before the bot resumes.
UPDATE feishu_deal_bot_settings
SET enabled=false, updated_at=now(), version=version+1
WHERE enabled=true AND financial_visibility_acknowledged_at IS NULL;

CREATE TABLE feishu_deal_bot_pending_actions (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    session_id uuid NOT NULL REFERENCES feishu_deal_bot_sessions(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    action_type varchar(32) NOT NULL,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    target_membership_id uuid NOT NULL REFERENCES memberships(id),
    expected_customer_version bigint NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    expires_at timestamptz NOT NULL,
    applied_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_deal_pending_action_type_check CHECK (action_type IN ('OWNER_TRANSFER')),
    CONSTRAINT feishu_deal_pending_action_status_check CHECK (
        status IN ('PENDING','APPLIED','CANCELLED','EXPIRED')
    )
);
CREATE UNIQUE INDEX uq_feishu_deal_pending_owner_transfer
    ON feishu_deal_bot_pending_actions(session_id,action_type)
    WHERE status='PENDING';
CREATE INDEX feishu_deal_pending_action_expiry_idx
    ON feishu_deal_bot_pending_actions(status,expires_at);

UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id,version,title,summary,sections,released_at,published,current
) VALUES (
    '20000000-0000-0000-0000-000000000031',
    '2026.09.06.2',
    '飞书成单快速建档与 Slash 工作流',
    '成单机器人新增快速建档、负责人转接、订单金额、完整 Slash 命令和增强榜单。',
    '{"新增":["飞书群支持 /new 快速创建正式客户并进行同名核对","新增客户负责人二次确认转接","新增订单金额、主成单人、协助人和奖金的完整 Slash 流程"],"改进":["榜单和成单广播增加订单金额","机器人 /help 和会话状态更清晰"],"安全":["转接使用一次性确认与版本校验","所有命令继续绑定当前组织、成员和指定群聊"]}'::jsonb,
    '2026-09-06T18:00:00+08:00',true,true
);
