ALTER TABLE customers ADD COLUMN high_school varchar(200);
ALTER TABLE customers ADD COLUMN source_location varchar(200);

ALTER TABLE reminders ADD COLUMN reminder_type varchar(32) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE reminders ADD COLUMN automatic_day date;
ALTER TABLE reminders ADD COLUMN related_resource_id uuid;
ALTER TABLE reminders ADD CONSTRAINT reminder_type_check
    CHECK (reminder_type IN ('MANUAL','A_CLASS_STALE','A_CLASS_OWNER_MISSING','CONSULTATION_FEEDBACK'));
CREATE UNIQUE INDEX uq_pending_automatic_reminder
    ON reminders (organization_id, assigned_membership_id, reminder_type, related_resource_id)
    WHERE reminder_type<>'MANUAL' AND status='PENDING' AND related_resource_id IS NOT NULL;

CREATE TABLE organization_follow_up_policies (
    organization_id uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    a_class_stale_enabled boolean NOT NULL DEFAULT true,
    stale_after_hours integer NOT NULL DEFAULT 72,
    notify_at time NOT NULL DEFAULT '09:00',
    qq_enabled boolean NOT NULL DEFAULT false,
    updated_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT follow_up_stale_hours_check CHECK (stale_after_hours BETWEEN 24 AND 720)
);
INSERT INTO organization_follow_up_policies (organization_id)
SELECT id FROM organizations ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION seed_follow_up_policy_for_organization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO organization_follow_up_policies (organization_id) VALUES (NEW.id) ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER organizations_seed_follow_up_policy
AFTER INSERT ON organizations FOR EACH ROW EXECUTE FUNCTION seed_follow_up_policy_for_organization();

CREATE TABLE follow_up_recruitments (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    required_count integer NOT NULL DEFAULT 1,
    claimed_count integer NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    opened_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT follow_up_recruitment_status_check CHECK (status IN ('OPEN','CLOSED','CANCELLED')),
    CONSTRAINT follow_up_recruitment_count_check CHECK (
        required_count BETWEEN 1 AND 20 AND claimed_count BETWEEN 0 AND required_count
    )
);
CREATE UNIQUE INDEX uq_open_follow_up_recruitment
    ON follow_up_recruitments (organization_id, customer_id) WHERE status='OPEN';
CREATE INDEX follow_up_recruitment_org_status_idx
    ON follow_up_recruitments (organization_id, status, opened_at DESC);

CREATE TABLE follow_up_recruitment_claims (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    recruitment_id uuid NOT NULL REFERENCES follow_up_recruitments(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    claimed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT follow_up_claim_unique UNIQUE (organization_id, recruitment_id, membership_id)
);

CREATE TABLE consultation_settings (
    organization_id uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    business_start time NOT NULL DEFAULT '09:00',
    business_end time NOT NULL DEFAULT '21:00',
    minimum_lead_minutes integer NOT NULL DEFAULT 30,
    allowed_durations jsonb NOT NULL DEFAULT '[30,45,60,90]'::jsonb,
    daily_capacity integer NOT NULL DEFAULT 6,
    feedback_due_hours integer NOT NULL DEFAULT 48,
    updated_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT consultation_business_hours_check CHECK (business_start < business_end),
    CONSTRAINT consultation_lead_check CHECK (minimum_lead_minutes BETWEEN 0 AND 10080),
    CONSTRAINT consultation_capacity_check CHECK (daily_capacity BETWEEN 1 AND 100),
    CONSTRAINT consultation_feedback_check CHECK (feedback_due_hours BETWEEN 1 AND 720)
);
INSERT INTO consultation_settings (organization_id)
SELECT id FROM organizations ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION seed_consultation_settings_for_organization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO consultation_settings (organization_id) VALUES (NEW.id) ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER organizations_seed_consultation_settings
AFTER INSERT ON organizations FOR EACH ROW EXECUTE FUNCTION seed_consultation_settings_for_organization();

CREATE TABLE consultation_failure_reasons (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    code varchar(80) NOT NULL,
    label varchar(160) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT consultation_failure_reason_unique UNIQUE (organization_id, code)
);

INSERT INTO consultation_failure_reasons (id, organization_id, code, label, sort_order)
SELECT gen_random_uuid(), organization.id, reason.code, reason.label, reason.sort_order
FROM organizations organization
CROSS JOIN (VALUES
    ('NO_INTEREST', '暂无明确意愿', 10),
    ('NOT_A_FIT', '需求或产品不匹配', 20),
    ('BUDGET', '预算原因', 30),
    ('TIMING', '当前时间不合适', 40),
    ('UNREACHABLE', '无法联系', 50),
    ('OTHER', '其他', 999)
) AS reason(code, label, sort_order)
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION seed_consultation_settings_for_organization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO consultation_settings (organization_id) VALUES (NEW.id) ON CONFLICT DO NOTHING;
    INSERT INTO consultation_failure_reasons (id, organization_id, code, label, sort_order)
    SELECT gen_random_uuid(), NEW.id, reason.code, reason.label, reason.sort_order
    FROM (VALUES
        ('NO_INTEREST', '暂无明确意愿', 10),
        ('NOT_A_FIT', '需求或产品不匹配', 20),
        ('BUDGET', '预算原因', 30),
        ('TIMING', '当前时间不合适', 40),
        ('UNREACHABLE', '无法联系', 50),
        ('OTHER', '其他', 999)
    ) AS reason(code, label, sort_order)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TABLE consultation_handoffs (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    customer_snapshot jsonb NOT NULL,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    consultant_membership_id uuid NOT NULL REFERENCES memberships(id),
    source_interaction_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    consultation_reason text NOT NULL,
    customer_quotes text NOT NULL,
    goals_motivation text NOT NULL,
    actions_taken text NOT NULL,
    main_concerns text NOT NULL,
    decision_participants text,
    decision_participant_consent boolean NOT NULL DEFAULT false,
    product_fit text NOT NULL,
    suggested_focus text NOT NULL,
    explicit_taboos text NOT NULL,
    expected_result text NOT NULL,
    scheduled_start timestamptz NOT NULL,
    scheduled_end timestamptz NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING_ACCEPTANCE',
    rejection_reason text,
    accepted_at timestamptz,
    actual_ended_at timestamptz,
    feedback_due_at timestamptz,
    result_summary text,
    continue_follow_up boolean,
    next_step text,
    next_owner_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    next_follow_up_at timestamptz,
    failure_reason_id uuid REFERENCES consultation_failure_reasons(id) ON DELETE SET NULL,
    failure_reason_other text,
    result_interaction_id uuid REFERENCES interactions(id) ON DELETE SET NULL,
    completed_at timestamptz,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT consultation_status_check CHECK (
        status IN ('PENDING_ACCEPTANCE','SCHEDULED','AWAITING_FEEDBACK','COMPLETED','REJECTED','CANCELLED')
    ),
    CONSTRAINT consultation_schedule_check CHECK (scheduled_end > scheduled_start),
    CONSTRAINT consultation_decision_consent_check CHECK (
        decision_participants IS NULL OR decision_participant_consent=true
    )
);
CREATE INDEX consultation_org_status_idx
    ON consultation_handoffs (organization_id, status, scheduled_start);
CREATE INDEX consultation_consultant_schedule_idx
    ON consultation_handoffs (organization_id, consultant_membership_id, scheduled_start, scheduled_end)
    WHERE status IN ('PENDING_ACCEPTANCE','SCHEDULED','AWAITING_FEEDBACK');

CREATE TABLE qq_connections (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    bridge_instance_id varchar(160),
    encrypted_access_token text,
    masked_qq varchar(80),
    status varchar(24) NOT NULL DEFAULT 'DISCONNECTED',
    last_heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT qq_connection_status_check CHECK (
        status IN ('DISCONNECTED','PAIRING','CONNECTED','ERROR','REVOKED')
    )
);
CREATE UNIQUE INDEX uq_active_qq_connection
    ON qq_connections (organization_id, membership_id) WHERE revoked_at IS NULL;

CREATE TABLE qq_imports (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    connection_id uuid NOT NULL REFERENCES qq_connections(id) ON DELETE CASCADE,
    peer_qq varchar(120) NOT NULL,
    range_start timestamptz NOT NULL,
    range_end timestamptz NOT NULL,
    message_count integer NOT NULL,
    message_hash varchar(64) NOT NULL,
    encrypted_payload_path text NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PREVIEWED',
    idempotency_key_hash varchar(64),
    draft jsonb,
    interaction_id uuid REFERENCES interactions(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    CONSTRAINT qq_import_status_check CHECK (status IN ('PREVIEWED','CONFIRMED','EXPIRED','FAILED')),
    CONSTRAINT qq_import_range_check CHECK (range_end >= range_start),
    CONSTRAINT qq_import_message_count_check CHECK (message_count BETWEEN 1 AND 10000),
    CONSTRAINT qq_import_message_unique UNIQUE (organization_id, connection_id, peer_qq, message_hash)
);
CREATE INDEX qq_import_expiry_idx ON qq_imports (expires_at) WHERE status='PREVIEWED';
CREATE UNIQUE INDEX uq_qq_import_idempotency
    ON qq_imports (organization_id, membership_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;

CREATE TABLE customer_enrichment_jobs (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    filename varchar(255) NOT NULL,
    file_sha256 varchar(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PREVIEWED',
    total_rows integer NOT NULL DEFAULT 0,
    ready_rows integer NOT NULL DEFAULT 0,
    updated_rows integer NOT NULL DEFAULT 0,
    skipped_rows integer NOT NULL DEFAULT 0,
    conflict_rows integer NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT customer_enrichment_status_check CHECK (status IN ('PREVIEWED','COMPLETED','EXPIRED')),
    CONSTRAINT customer_enrichment_file_unique UNIQUE (organization_id, file_sha256)
);

CREATE TABLE customer_enrichment_rows (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES customer_enrichment_jobs(id) ON DELETE CASCADE,
    row_number integer NOT NULL,
    full_name varchar(160) NOT NULL,
    major varchar(160) NOT NULL,
    grade varchar(80) NOT NULL,
    high_school varchar(200) NOT NULL,
    source_location varchar(200),
    mapping_status varchar(40) NOT NULL,
    customer_id uuid REFERENCES customers(id) ON DELETE SET NULL,
    row_status varchar(32) NOT NULL,
    message varchar(500),
    applied_high_school boolean NOT NULL DEFAULT false,
    applied_source_location boolean NOT NULL DEFAULT false,
    CONSTRAINT customer_enrichment_row_unique UNIQUE (job_id, row_number),
    CONSTRAINT customer_enrichment_row_status_check CHECK (
        row_status IN ('READY','UPDATED','ALREADY_PRESENT','NO_CUSTOMER','AMBIGUOUS','GRADE_MISMATCH','UNVERIFIED_LOCATION','CONFLICT','INVALID')
    )
);
CREATE INDEX customer_enrichment_job_rows_idx ON customer_enrichment_rows (job_id, row_status, row_number);
