ALTER TABLE organization_follow_up_policies
    ADD COLUMN ab_auto_release_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN ab_auto_release_effective_from timestamptz;

CREATE TABLE customer_deal_forecasts (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    business_cycle_id uuid NOT NULL REFERENCES customer_business_cycles(id) ON DELETE CASCADE,
    submitted_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    evidence_types jsonb NOT NULL DEFAULT '[]'::jsonb,
    other_evidence_description varchar(1000),
    fact_summary text NOT NULL,
    expected_close_date date NOT NULL,
    current_obstacle text,
    next_action text NOT NULL,
    next_action_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING_REVIEW',
    review_note text,
    reviewed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    reviewed_at timestamptz,
    outcome varchar(32),
    closed_at timestamptz,
    idempotency_key_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT deal_forecast_status_check CHECK (
        status IN ('PENDING_REVIEW','CONFIRMED','RETURNED','WITHDRAWN','CLOSED')
    ),
    CONSTRAINT deal_forecast_outcome_check CHECK (
        outcome IS NULL OR outcome IN ('WON','LOST_TO_COMPETITOR','CANCELLED')
    ),
    CONSTRAINT deal_forecast_evidence_types_array CHECK (jsonb_typeof(evidence_types)='array'),
    CONSTRAINT deal_forecast_idempotency_unique UNIQUE (
        organization_id, submitted_by_membership_id, idempotency_key_hash
    )
);
CREATE UNIQUE INDEX uq_active_deal_forecast
    ON customer_deal_forecasts (organization_id, business_cycle_id)
    WHERE status IN ('PENDING_REVIEW','CONFIRMED','RETURNED');
CREATE INDEX deal_forecast_admin_filter_idx
    ON customer_deal_forecasts (organization_id, status, expected_close_date, updated_at DESC);
CREATE INDEX deal_forecast_submitter_idx
    ON customer_deal_forecasts (organization_id, submitted_by_membership_id, updated_at DESC);

CREATE TABLE customer_deal_forecast_evidence (
    forecast_id uuid NOT NULL REFERENCES customer_deal_forecasts(id) ON DELETE CASCADE,
    interaction_id uuid NOT NULL REFERENCES interactions(id) ON DELETE RESTRICT,
    linked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (forecast_id, interaction_id)
);
CREATE INDEX deal_forecast_evidence_interaction_idx
    ON customer_deal_forecast_evidence (interaction_id, forecast_id);

CREATE TABLE customer_deal_forecast_events (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    forecast_id uuid NOT NULL REFERENCES customer_deal_forecasts(id) ON DELETE CASCADE,
    actor_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    event_type varchar(40) NOT NULL,
    previous_status varchar(24),
    new_status varchar(24) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX deal_forecast_event_history_idx
    ON customer_deal_forecast_events (organization_id, forecast_id, created_at, id);

CREATE OR REPLACE FUNCTION reject_deal_forecast_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'deal forecast event history is append-only';
END;
$$;
CREATE TRIGGER customer_deal_forecast_events_append_only
BEFORE UPDATE OR DELETE ON customer_deal_forecast_events
FOR EACH ROW EXECUTE FUNCTION reject_deal_forecast_event_mutation();

UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000015',
    '2026.08.19.1',
    '成交预测与市场治理查询',
    '销售可以提交有沟通依据的高意向成交预测，管理端可集中审核并快速查询交叉单。',
    '{"新增":["高意向成单申报、证据关联和管理员确认","市场治理台交叉单与成交预测快速查询"],"改进":["A/B 超时客户保留提醒并暂停自动释放","C 类与人工公共池流程保持不变"],"安全":["组织权限裁剪、幂等写入和不可覆盖审核记录"]}'::jsonb,
    '2026-08-19T12:00:00+08:00',
    true,
    true
);
