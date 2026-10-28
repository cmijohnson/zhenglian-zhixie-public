CREATE TABLE customer_business_cycles (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    cycle_number integer NOT NULL,
    cycle_type varchar(24) NOT NULL,
    title varchar(200) NOT NULL,
    summary text,
    status varchar(32) NOT NULL DEFAULT 'IN_PROGRESS',
    cross_sale boolean NOT NULL DEFAULT false,
    current_snapshot_version integer NOT NULL DEFAULT 0,
    suggested_primary_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    suggested_assistant_membership_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    lock_request_reason text,
    lock_requested_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    lock_requested_at timestamptz,
    quote_type varchar(120),
    quoted_at timestamptz,
    quote_confirmed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    participant_locked_at timestamptz,
    won_declaration_fact text,
    won_declared_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    won_declared_at timestamptz,
    won_confirmed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    won_confirmed_at timestamptz,
    emergency_exception_reason text,
    competitor_name varchar(200),
    competitor_fact text,
    status_marked_at timestamptz NOT NULL DEFAULT now(),
    idempotency_key_hash varchar(64) NOT NULL,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT customer_business_cycle_number_check CHECK (cycle_number >= 1),
    CONSTRAINT customer_business_cycle_type_check CHECK (cycle_type IN ('INITIAL','REPURCHASE')),
    CONSTRAINT customer_business_cycle_status_check CHECK (
        status IN ('IN_PROGRESS','LOCK_PENDING','FORMALLY_QUOTED','WON_PENDING','WON','LOST_TO_COMPETITOR','CANCELLED')
    ),
    CONSTRAINT customer_business_cycle_snapshot_check CHECK (current_snapshot_version >= 0),
    CONSTRAINT customer_business_cycle_number_unique UNIQUE (organization_id, customer_id, cycle_number),
    CONSTRAINT customer_business_cycle_idempotency_unique UNIQUE (
        organization_id, created_by_membership_id, idempotency_key_hash
    )
);
CREATE UNIQUE INDEX uq_customer_active_business_cycle
    ON customer_business_cycles (organization_id, customer_id)
    WHERE status IN ('IN_PROGRESS','LOCK_PENDING','FORMALLY_QUOTED','WON_PENDING');

CREATE TABLE customer_business_participant_change_requests (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    business_cycle_id uuid NOT NULL REFERENCES customer_business_cycles(id) ON DELETE CASCADE,
    proposed_primary_membership_id uuid NOT NULL REFERENCES memberships(id),
    proposed_assistant_membership_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    affected_membership_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    reason text NOT NULL,
    expected_cycle_version bigint NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT participant_change_status_check CHECK (status IN ('PENDING','READY','APPLIED','CANCELLED'))
);
CREATE UNIQUE INDEX uq_pending_participant_change
    ON customer_business_participant_change_requests (business_cycle_id)
    WHERE status IN ('PENDING','READY');

CREATE TABLE customer_business_participant_change_approvals (
    request_id uuid NOT NULL REFERENCES customer_business_participant_change_requests(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    approved_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, membership_id)
);
CREATE INDEX customer_business_cycle_admin_filter_idx
    ON customer_business_cycles (organization_id, status, status_marked_at DESC);
CREATE INDEX customer_business_cycle_customer_idx
    ON customer_business_cycles (organization_id, customer_id, cycle_number DESC);

CREATE TABLE customer_business_participant_snapshots (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    business_cycle_id uuid NOT NULL REFERENCES customer_business_cycles(id) ON DELETE CASCADE,
    snapshot_version integer NOT NULL,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    role_type varchar(24) NOT NULL,
    display_name_snapshot varchar(160) NOT NULL,
    confirmed_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    confirmation_reason text,
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_business_participant_version_check CHECK (snapshot_version >= 1),
    CONSTRAINT customer_business_participant_role_check CHECK (role_type IN ('PRIMARY','ASSISTANT')),
    CONSTRAINT customer_business_participant_unique UNIQUE (
        business_cycle_id, snapshot_version, membership_id
    )
);
CREATE UNIQUE INDEX uq_customer_business_snapshot_primary
    ON customer_business_participant_snapshots (business_cycle_id, snapshot_version)
    WHERE role_type='PRIMARY';
CREATE INDEX customer_business_snapshot_member_idx
    ON customer_business_participant_snapshots (organization_id, membership_id, role_type);

CREATE TABLE customer_business_cycle_events (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    business_cycle_id uuid NOT NULL REFERENCES customer_business_cycles(id) ON DELETE CASCADE,
    actor_membership_id uuid NOT NULL REFERENCES memberships(id),
    action varchar(48) NOT NULL,
    previous_status varchar(32),
    new_status varchar(32),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX customer_business_cycle_event_history_idx
    ON customer_business_cycle_events (organization_id, business_cycle_id, created_at, id);

CREATE TABLE customer_referrals (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    referrer_customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    referred_customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    source_business_cycle_id uuid REFERENCES customer_business_cycles(id) ON DELETE SET NULL,
    relationship_note text,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    idempotency_key_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_referral_distinct_customer_check CHECK (referrer_customer_id<>referred_customer_id),
    CONSTRAINT customer_referral_pair_unique UNIQUE (
        organization_id, referrer_customer_id, referred_customer_id
    ),
    CONSTRAINT customer_referral_idempotency_unique UNIQUE (
        organization_id, created_by_membership_id, idempotency_key_hash
    )
);
CREATE INDEX customer_referral_referred_idx
    ON customer_referrals (organization_id, referred_customer_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_customer_business_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'customer business history is append-only';
END;
$$;

CREATE TRIGGER customer_business_participant_snapshots_append_only
BEFORE UPDATE OR DELETE ON customer_business_participant_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_customer_business_history_mutation();

CREATE TRIGGER customer_business_cycle_events_append_only
BEFORE UPDATE OR DELETE ON customer_business_cycle_events
FOR EACH ROW EXECUTE FUNCTION reject_customer_business_history_mutation();

CREATE TRIGGER customer_referrals_append_only
BEFORE UPDATE OR DELETE ON customer_referrals
FOR EACH ROW EXECUTE FUNCTION reject_customer_business_history_mutation();
