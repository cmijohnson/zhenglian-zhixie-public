ALTER TABLE interactions
    ADD COLUMN interaction_type varchar(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN content_fingerprint varchar(64),
    ADD COLUMN deadline_effect varchar(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN deadline_effect_reason varchar(160);

ALTER TABLE interactions ADD CONSTRAINT interaction_type_check CHECK (
    interaction_type IN ('LEGACY','CUSTOMER_REPLY','OUTBOUND_NO_REPLY','AGREED_LATER','INTERNAL_NOTE')
);
ALTER TABLE interactions ADD CONSTRAINT interaction_deadline_effect_check CHECK (
    deadline_effect IN ('LEGACY','APPLIED','IGNORED')
);
CREATE INDEX interactions_content_fingerprint_idx
    ON interactions (organization_id, customer_id, content_fingerprint)
    WHERE content_fingerprint IS NOT NULL;

CREATE TABLE customer_classifications (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    revision integer NOT NULL,
    level varchar(1) NOT NULL,
    recognition_reason text NOT NULL,
    potential_demand text NOT NULL,
    a_signals jsonb NOT NULL DEFAULT '[]'::jsonb,
    evidence_interaction_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    submitted_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    source varchar(32) NOT NULL DEFAULT 'STRUCTURED',
    correction_reason text,
    previous_classification_id uuid REFERENCES customer_classifications(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_classification_level_check CHECK (level IN ('A','B','C')),
    CONSTRAINT customer_classification_source_check CHECK (
        source IN ('STRUCTURED','ADMIN_CORRECTION','LEGACY_IMPORT')
    ),
    CONSTRAINT customer_classification_revision_unique UNIQUE (customer_id, revision),
    CONSTRAINT customer_classification_a_signal_check CHECK (
        level <> 'A' OR jsonb_array_length(a_signals) > 0
    ),
    CONSTRAINT customer_classification_evidence_check CHECK (
        source = 'LEGACY_IMPORT' OR jsonb_array_length(evidence_interaction_ids) > 0
    )
);
CREATE INDEX customer_classification_current_idx
    ON customer_classifications (organization_id, customer_id, revision DESC);
CREATE INDEX customer_classification_actor_time_idx
    ON customer_classifications (organization_id, submitted_by_membership_id, created_at DESC)
    WHERE source <> 'LEGACY_IMPORT';

CREATE TABLE customer_classification_review_queue (
    classification_id uuid PRIMARY KEY REFERENCES customer_classifications(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    recommendation_month date NOT NULL,
    recommended boolean NOT NULL DEFAULT false,
    selected_at timestamptz,
    selected_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    review_status varchar(24) NOT NULL DEFAULT 'PENDING',
    reviewed_at timestamptz,
    reviewed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    result_classification_id uuid REFERENCES customer_classifications(id) ON DELETE RESTRICT,
    review_note text,
    CONSTRAINT classification_review_status_check CHECK (
        review_status IN ('PENDING','CONFIRMED','CORRECTED')
    )
);
CREATE INDEX classification_review_month_idx
    ON customer_classification_review_queue (organization_id, recommendation_month, recommended, review_status);

CREATE TABLE market_deadline_rule_versions (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    version integer NOT NULL,
    effective_from timestamptz NOT NULL,
    mode varchar(16) NOT NULL DEFAULT 'SHADOW',
    peak_periods jsonb NOT NULL DEFAULT '[]'::jsonb,
    pause_periods jsonb NOT NULL DEFAULT '[]'::jsonb,
    a_peak_hours integer NOT NULL DEFAULT 72,
    b_peak_hours integer NOT NULL DEFAULT 96,
    a_off_peak_hours integer NOT NULL DEFAULT 168,
    b_off_peak_hours integer NOT NULL DEFAULT 216,
    weekends_count boolean NOT NULL DEFAULT true,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT market_deadline_rule_org_version_unique UNIQUE (organization_id, version),
    CONSTRAINT market_deadline_rule_mode_check CHECK (mode IN ('SHADOW','ENFORCED')),
    CONSTRAINT market_deadline_rule_hours_check CHECK (
        a_peak_hours BETWEEN 1 AND 2160 AND b_peak_hours BETWEEN 1 AND 2160
        AND a_off_peak_hours BETWEEN 1 AND 2160 AND b_off_peak_hours BETWEEN 1 AND 2160
    )
);
CREATE INDEX market_deadline_rule_effective_idx
    ON market_deadline_rule_versions (organization_id, effective_from DESC, version DESC);

INSERT INTO market_deadline_rule_versions (
    id, organization_id, version, effective_from, mode,
    a_peak_hours, b_peak_hours, a_off_peak_hours, b_off_peak_hours, weekends_count
)
SELECT gen_random_uuid(), organization.id, 1, '-infinity'::timestamptz, 'SHADOW', 72, 96, 168, 216, true
FROM organizations organization
ON CONFLICT (organization_id, version) DO NOTHING;

CREATE OR REPLACE FUNCTION seed_market_deadline_rule_for_organization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO market_deadline_rule_versions (
        id, organization_id, version, effective_from, mode,
        a_peak_hours, b_peak_hours, a_off_peak_hours, b_off_peak_hours, weekends_count
    ) VALUES (gen_random_uuid(), NEW.id, 1, '-infinity'::timestamptz, 'SHADOW', 72, 96, 168, 216, true)
    ON CONFLICT (organization_id, version) DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER organizations_seed_market_deadline_rule
AFTER INSERT ON organizations FOR EACH ROW EXECUTE FUNCTION seed_market_deadline_rule_for_organization();

CREATE TABLE customer_deadline_snapshots (
    customer_id uuid PRIMARY KEY REFERENCES customers(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    cycle_id uuid NOT NULL,
    ownership_cycle bigint NOT NULL DEFAULT 0,
    classification_level varchar(1) NOT NULL,
    rule_version_id uuid NOT NULL REFERENCES market_deadline_rule_versions(id) ON DELETE RESTRICT,
    base_interaction_id uuid REFERENCES interactions(id) ON DELETE SET NULL,
    computed_deadline timestamptz NOT NULL,
    no_reply_extension_used boolean NOT NULL DEFAULT false,
    mode varchar(16) NOT NULL DEFAULT 'SHADOW',
    calculated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_deadline_level_check CHECK (classification_level IN ('A','B')),
    CONSTRAINT customer_deadline_mode_check CHECK (mode IN ('SHADOW','ENFORCED'))
);
CREATE INDEX customer_deadline_due_idx
    ON customer_deadline_snapshots (organization_id, mode, computed_deadline);

CREATE TABLE customer_deadline_events (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    cycle_id uuid NOT NULL,
    interaction_id uuid REFERENCES interactions(id) ON DELETE SET NULL,
    classification_id uuid REFERENCES customer_classifications(id) ON DELETE SET NULL,
    event_type varchar(40) NOT NULL,
    applied boolean NOT NULL,
    reason varchar(160) NOT NULL,
    previous_deadline timestamptz,
    calculated_deadline timestamptz,
    rule_version_id uuid REFERENCES market_deadline_rule_versions(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX customer_deadline_event_customer_idx
    ON customer_deadline_events (organization_id, customer_id, created_at DESC);

CREATE OR REPLACE FUNCTION prevent_market_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'market history rows are append-only';
END;
$$;
CREATE TRIGGER customer_classifications_append_only
BEFORE UPDATE OR DELETE ON customer_classifications
FOR EACH ROW EXECUTE FUNCTION prevent_market_history_mutation();
CREATE TRIGGER customer_deadline_events_append_only
BEFORE UPDATE OR DELETE ON customer_deadline_events
FOR EACH ROW EXECUTE FUNCTION prevent_market_history_mutation();

INSERT INTO customer_classifications (
    id, organization_id, customer_id, revision, level, recognition_reason, potential_demand,
    a_signals, evidence_interaction_ids, submitted_by_membership_id, team_id_snapshot, source, created_at
)
SELECT gen_random_uuid(), customer.organization_id, customer.id, 1,
       CASE quality_tag.name WHEN 'A类用户' THEN 'A' WHEN 'B类用户' THEN 'B' ELSE 'C' END,
       '历史标签迁移，待补充结构化认定依据', '未确认',
       CASE WHEN quality_tag.name='A类用户' THEN '["历史A类标签，待复核"]'::jsonb ELSE '[]'::jsonb END,
       '[]'::jsonb, customer.created_by_membership_id, customer.team_id, 'LEGACY_IMPORT', now()
FROM customers customer
JOIN LATERAL (
    SELECT tag.name
    FROM customer_tags customer_tag
    JOIN tags tag ON tag.id=customer_tag.tag_id
    WHERE customer_tag.customer_id=customer.id AND tag.category='客户质量'
    ORDER BY tag.name
    LIMIT 1
) quality_tag ON true
ON CONFLICT (customer_id, revision) DO NOTHING;

INSERT INTO customer_deadline_snapshots (
    customer_id, organization_id, cycle_id, ownership_cycle, classification_level, rule_version_id,
    base_interaction_id, computed_deadline, no_reply_extension_used, mode, calculated_at
)
SELECT customer.id, customer.organization_id,
       md5(customer.organization_id::text || customer.id::text || ':legacy-cycle')::uuid,
       0, classification.level, rule.id, null,
       coalesce(customer.last_contact_at, customer.created_at)
           + CASE classification.level WHEN 'A' THEN interval '168 hours' ELSE interval '216 hours' END,
       false, 'SHADOW', now()
FROM customers customer
JOIN LATERAL (
    SELECT item.id, item.level
    FROM customer_classifications item
    WHERE item.customer_id=customer.id
    ORDER BY item.revision DESC LIMIT 1
) classification ON classification.level IN ('A','B')
JOIN market_deadline_rule_versions rule
  ON rule.organization_id=customer.organization_id AND rule.version=1
ON CONFLICT (customer_id) DO NOTHING;

INSERT INTO customer_deadline_events (
    id, organization_id, customer_id, cycle_id, classification_id, event_type,
    applied, reason, calculated_deadline, rule_version_id
)
SELECT gen_random_uuid(), snapshot.organization_id, snapshot.customer_id, snapshot.cycle_id,
       classification.id, 'CLASSIFICATION_CHANGED', true, 'LEGACY_SHADOW_INITIALIZED',
       snapshot.computed_deadline, snapshot.rule_version_id
FROM customer_deadline_snapshots snapshot
JOIN LATERAL (
    SELECT item.id FROM customer_classifications item
    WHERE item.customer_id=snapshot.customer_id
    ORDER BY item.revision DESC LIMIT 1
) classification ON true;
