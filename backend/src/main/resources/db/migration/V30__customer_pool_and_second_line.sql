CREATE TABLE customer_pool_entries (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    pool_type varchar(16) NOT NULL,
    release_reason varchar(40) NOT NULL,
    release_note varchar(1000),
    previous_owner_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    priority_owner_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    priority_until timestamptz,
    available_at timestamptz NOT NULL DEFAULT now(),
    status varchar(24) NOT NULL DEFAULT 'OPEN',
    temporary_claim_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    temporary_claim_expires_at timestamptz,
    opened_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT customer_pool_type_check CHECK (pool_type IN ('AB','C')),
    CONSTRAINT customer_pool_status_check CHECK (status IN ('OPEN','TEMP_CLAIMED','CLOSED','CANCELLED')),
    CONSTRAINT customer_pool_temp_shape_check CHECK (
        (status='TEMP_CLAIMED' AND temporary_claim_membership_id IS NOT NULL AND temporary_claim_expires_at IS NOT NULL)
        OR status<>'TEMP_CLAIMED'
    )
);
CREATE UNIQUE INDEX uq_customer_active_pool_entry
    ON customer_pool_entries (organization_id, customer_id)
    WHERE status IN ('OPEN','TEMP_CLAIMED');
CREATE INDEX idx_customer_pool_browse
    ON customer_pool_entries (organization_id, pool_type, status, available_at, opened_at);

CREATE TABLE customer_ownership_states (
    customer_id uuid PRIMARY KEY REFERENCES customers(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    status varchar(24) NOT NULL DEFAULT 'ASSIGNED',
    active_pool_entry_id uuid REFERENCES customer_pool_entries(id) ON DELETE SET NULL,
    previous_owner_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    temporary_claim_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    temporary_claim_expires_at timestamptz,
    ownership_cycle bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT customer_ownership_status_check CHECK (
        status IN ('ASSIGNED','POOLED_AB','POOLED_C','TEMP_CLAIMED','FROZEN_TAKEOVER')
    ),
    CONSTRAINT customer_ownership_temp_shape_check CHECK (
        (status='TEMP_CLAIMED' AND temporary_claim_membership_id IS NOT NULL AND temporary_claim_expires_at IS NOT NULL)
        OR status<>'TEMP_CLAIMED'
    )
);
CREATE INDEX idx_customer_ownership_state
    ON customer_ownership_states (organization_id, status, updated_at);

CREATE TABLE customer_claim_records (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    pool_entry_id uuid NOT NULL REFERENCES customer_pool_entries(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    claimant_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE RESTRICT,
    status varchar(20) NOT NULL DEFAULT 'TEMPORARY',
    idempotency_key varchar(120) NOT NULL,
    claimed_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    confirmed_at timestamptz,
    confirming_interaction_id uuid REFERENCES interactions(id) ON DELETE SET NULL,
    ended_at timestamptz,
    CONSTRAINT customer_claim_status_check CHECK (status IN ('TEMPORARY','CONFIRMED','EXPIRED','CANCELLED')),
    CONSTRAINT customer_claim_idempotency_unique UNIQUE (organization_id, claimant_membership_id, idempotency_key)
);
CREATE UNIQUE INDEX uq_customer_active_claim
    ON customer_claim_records (organization_id, pool_entry_id)
    WHERE status='TEMPORARY';
CREATE INDEX idx_customer_claim_expiry ON customer_claim_records (status, expires_at);

CREATE TABLE customer_claim_cooldowns (
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    cooldown_until timestamptz NOT NULL,
    reason varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, customer_id, membership_id)
);

CREATE TABLE customer_role_assignments (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE RESTRICT,
    role_type varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    assigned_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    assignment_source varchar(32) NOT NULL DEFAULT 'MANUAL',
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT customer_role_type_check CHECK (role_type IN ('COLLABORATOR','A_SECOND_LINE','DEAL_ASSISTANT')),
    CONSTRAINT customer_role_status_check CHECK (status IN ('ACTIVE','ENDED'))
);
CREATE UNIQUE INDEX uq_customer_active_role_assignment
    ON customer_role_assignments (organization_id, customer_id, membership_id, role_type)
    WHERE status='ACTIVE';
CREATE INDEX idx_customer_role_lookup
    ON customer_role_assignments (organization_id, customer_id, role_type, status);

INSERT INTO customer_role_assignments
    (id, organization_id, customer_id, membership_id, role_type, status,
     assigned_by_membership_id, assignment_source, started_at)
SELECT gen_random_uuid(), grant_row.organization_id, grant_row.customer_id,
       grant_row.grantee_membership_id, 'COLLABORATOR', 'ACTIVE',
       grant_row.granted_by_membership_id, 'LEGACY_IMPORT', grant_row.created_at
FROM customer_access_grants grant_row
WHERE grant_row.grantee_membership_id IS NOT NULL AND grant_row.can_edit=true
ON CONFLICT DO NOTHING;

CREATE TABLE second_line_recruitments (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    required_count integer NOT NULL DEFAULT 1,
    claimed_count integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'VOLUNTEER_OPEN',
    opened_at timestamptz NOT NULL DEFAULT now(),
    volunteer_until timestamptz NOT NULL,
    closed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT second_line_recruitment_count_check CHECK (
        required_count BETWEEN 1 AND 2 AND claimed_count BETWEEN 0 AND required_count
    ),
    CONSTRAINT second_line_recruitment_status_check CHECK (
        status IN ('VOLUNTEER_OPEN','ADMIN_PENDING','CLOSED','CANCELLED')
    )
);
CREATE UNIQUE INDEX uq_customer_active_second_line_recruitment
    ON second_line_recruitments (organization_id, customer_id)
    WHERE status IN ('VOLUNTEER_OPEN','ADMIN_PENDING');
CREATE INDEX idx_second_line_recruitment_status
    ON second_line_recruitments (organization_id, status, volunteer_until);

CREATE TABLE second_line_contact_authorizations (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    requester_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE RESTRICT,
    owner_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE RESTRICT,
    purpose varchar(1000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING_OWNER',
    owner_deadline timestamptz NOT NULL,
    decided_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    decision_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT second_line_contact_auth_status_check CHECK (
        status IN ('PENDING_OWNER','ADMIN_PENDING','APPROVED','DENIED','EXPIRED')
    )
);
CREATE UNIQUE INDEX uq_pending_second_line_contact_auth
    ON second_line_contact_authorizations (organization_id, customer_id, requester_membership_id)
    WHERE status IN ('PENDING_OWNER','ADMIN_PENDING');
CREATE INDEX idx_second_line_contact_auth_deadline
    ON second_line_contact_authorizations (status, owner_deadline);

CREATE TABLE customer_takeover_tasks (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    previous_owner_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    reason varchar(40) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ADMIN_PENDING',
    selected_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    resolution_reason varchar(1000),
    CONSTRAINT customer_takeover_status_check CHECK (status IN ('ADMIN_PENDING','RESOLVED','CANCELLED'))
);
CREATE UNIQUE INDEX uq_customer_pending_takeover
    ON customer_takeover_tasks (organization_id, customer_id)
    WHERE status='ADMIN_PENDING';

CREATE TABLE customer_ownership_events (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    event_type varchar(48) NOT NULL,
    actor_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    previous_state varchar(24),
    resulting_state varchar(24) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_ownership_event_history
    ON customer_ownership_events (organization_id, customer_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_customer_ownership_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'customer ownership events are immutable';
END;
$$;
CREATE TRIGGER customer_ownership_events_immutable
BEFORE UPDATE OR DELETE ON customer_ownership_events
FOR EACH ROW EXECUTE FUNCTION reject_customer_ownership_event_mutation();
