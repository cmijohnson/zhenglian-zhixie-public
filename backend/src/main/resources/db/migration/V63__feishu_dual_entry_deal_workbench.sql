ALTER TABLE customer_deal_requests DROP CONSTRAINT customer_deal_request_source_check;
ALTER TABLE customer_deal_requests ADD CONSTRAINT customer_deal_request_source_check
    CHECK (source IN ('DIRECT','LEGACY_BUSINESS_CYCLE','FEISHU_BOT','FEISHU_WEB'));

ALTER TABLE customer_deal_requests DROP CONSTRAINT customer_deal_confirmation_channel_check;
ALTER TABLE customer_deal_requests ADD CONSTRAINT customer_deal_confirmation_channel_check CHECK (
    confirmation_channel IS NULL OR confirmation_channel IN (
        'WEB_ADMIN','ANDROID_ADMIN','FEISHU_BOT','FEISHU_WEB','LEGACY'
    )
);

CREATE TABLE feishu_deal_web_drafts (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    primary_membership_id uuid NOT NULL REFERENCES memberships(id),
    assistant_membership_ids uuid[] NOT NULL DEFAULT '{}',
    order_amount_cents bigint NOT NULL DEFAULT 0,
    bonus_total_cents bigint NOT NULL DEFAULT 0,
    allocations jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    confirmed_request_id uuid REFERENCES customer_deal_requests(id),
    expires_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_deal_web_draft_status_check CHECK (
        status IN ('ACTIVE','CONFIRMED','CANCELLED','EXPIRED')
    ),
    CONSTRAINT feishu_deal_web_draft_assistant_count_check CHECK (
        cardinality(assistant_membership_ids) BETWEEN 0 AND 2
    ),
    CONSTRAINT feishu_deal_web_draft_order_check CHECK (order_amount_cents >= 0),
    CONSTRAINT feishu_deal_web_draft_bonus_check CHECK (bonus_total_cents >= 0)
);
CREATE UNIQUE INDEX uq_feishu_deal_web_active_member
    ON feishu_deal_web_drafts(integration_id,created_by_membership_id)
    WHERE status='ACTIVE';
CREATE INDEX feishu_deal_web_draft_expiry_idx
    ON feishu_deal_web_drafts(status,expires_at);

UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id,version,title,summary,sections,released_at,published,current
) VALUES (
    '20000000-0000-0000-0000-000000000033',
    '2026.09.07.2',
    '飞书双入口与快捷成单工作台',
    '飞书工作台新增完整 CRM 入口和可恢复的可视化成单流程。',
    '{"新增":["飞书应用首页分为完整 CRM 和快捷成单两个入口","快捷成单支持客户搜索、建档、转接、参与人、金额、确认和榜单","飞书机器人支持一键打开 CRM 和网页成单"],"改进":["成单草稿保存30分钟并可在刷新后恢复","相同幂等键重试返回原成单结果"],"安全":["飞书页面继续使用服务端身份、组织和客户权限判定","深链不携带联系方式、金额或令牌"]}'::jsonb,
    '2026-09-07T12:00:00+08:00',true,true
);
