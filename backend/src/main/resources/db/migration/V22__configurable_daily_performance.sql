CREATE TABLE performance_rule_versions (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    version_no integer NOT NULL,
    effective_from date NOT NULL,
    daily_baseline numeric(10,1) NOT NULL DEFAULT 14.0,
    calendar_mode varchar(20) NOT NULL DEFAULT 'WEEKDAYS',
    weekdays jsonb NOT NULL DEFAULT '[1,2,3,4,5]'::jsonb,
    included_dates jsonb NOT NULL DEFAULT '[]'::jsonb,
    excluded_dates jsonb NOT NULL DEFAULT '[]'::jsonb,
    submission_opens_at time NOT NULL DEFAULT '09:00',
    submission_closes_at time NOT NULL DEFAULT '22:00',
    shortfall_mode varchar(24) NOT NULL DEFAULT 'STATUS_ONLY',
    ordinary_points numeric(10,1) NOT NULL DEFAULT 1.0,
    deep_points numeric(10,1) NOT NULL DEFAULT 2.0,
    ordinary_daily_cap integer,
    deep_daily_cap integer,
    event_tiers jsonb NOT NULL DEFAULT '[{"label":"Pro","minParticipants":50,"maxParticipants":null,"points":8.0}]'::jsonb,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT performance_rule_version_unique UNIQUE (organization_id, version_no),
    CONSTRAINT performance_rule_effective_unique UNIQUE (organization_id, effective_from),
    CONSTRAINT performance_rule_calendar_check CHECK (calendar_mode IN ('ALL_DAYS','WEEKDAYS','CUSTOM')),
    CONSTRAINT performance_rule_shortfall_check CHECK (shortfall_mode IN ('STATUS_ONLY','DEDUCT_GAP')),
    CONSTRAINT performance_rule_window_check CHECK (submission_opens_at < submission_closes_at),
    CONSTRAINT performance_rule_points_check CHECK (
        daily_baseline >= 0 AND ordinary_points >= 0 AND deep_points >= 0
        AND daily_baseline * 2 = trunc(daily_baseline * 2)
        AND ordinary_points * 2 = trunc(ordinary_points * 2)
        AND deep_points * 2 = trunc(deep_points * 2)
    ),
    CONSTRAINT performance_rule_cap_check CHECK (
        (ordinary_daily_cap IS NULL OR ordinary_daily_cap > 0)
        AND (deep_daily_cap IS NULL OR deep_daily_cap > 0)
    )
);
CREATE INDEX performance_rule_org_effective_idx
    ON performance_rule_versions (organization_id, effective_from DESC);

INSERT INTO performance_rule_versions (
    id, organization_id, version_no, effective_from, created_by_membership_id
)
SELECT gen_random_uuid(), organization.id, 1, CURRENT_DATE,
       (SELECT membership.id FROM memberships membership
        JOIN membership_roles membership_role ON membership_role.membership_id=membership.id
        JOIN roles role ON role.id=membership_role.role_id AND role.code='ORG_ADMIN'
        WHERE membership.organization_id=organization.id AND membership.status='ACTIVE'
        ORDER BY membership.created_at LIMIT 1)
FROM organizations organization
ON CONFLICT (organization_id, effective_from) DO NOTHING;

CREATE TABLE performance_participants (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    starts_on date NOT NULL,
    ends_on date,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT performance_participant_dates_check CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT performance_participant_period_unique UNIQUE (organization_id, membership_id, starts_on)
);
CREATE INDEX performance_participant_active_idx
    ON performance_participants (organization_id, membership_id, starts_on, ends_on);

CREATE TABLE performance_interaction_claims (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    interaction_id uuid NOT NULL REFERENCES interactions(id),
    customer_id uuid NOT NULL REFERENCES customers(id),
    performance_date date NOT NULL,
    requested_type varchar(20) NOT NULL,
    approved_type varchar(20),
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    review_reason varchar(2000),
    reviewed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    reviewed_at timestamptz,
    submitted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT performance_claim_interaction_unique UNIQUE (organization_id, interaction_id),
    CONSTRAINT performance_claim_type_check CHECK (requested_type IN ('ORDINARY','DEEP')),
    CONSTRAINT performance_claim_approved_type_check CHECK (approved_type IS NULL OR approved_type IN ('ORDINARY','DEEP')),
    CONSTRAINT performance_claim_status_check CHECK (status IN ('DRAFT','PENDING','APPROVED','REJECTED'))
);
CREATE INDEX performance_claim_review_idx
    ON performance_interaction_claims (organization_id, status, performance_date, created_at);
CREATE INDEX performance_claim_member_idx
    ON performance_interaction_claims (organization_id, membership_id, performance_date DESC);

CREATE TABLE performance_activities (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    organizer_membership_id uuid NOT NULL REFERENCES memberships(id),
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    event_date date NOT NULL,
    title varchar(200) NOT NULL,
    goal varchar(2000) NOT NULL,
    expected_participants integer NOT NULL,
    description varchar(6000) NOT NULL,
    actual_participants integer,
    verified_participants integer,
    tier_label varchar(120),
    total_points numeric(10,1),
    status varchar(32) NOT NULL DEFAULT 'PROPOSED',
    review_reason varchar(2000),
    reviewed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    approved_at timestamptz,
    completed_submitted_at timestamptz,
    allocated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT performance_activity_status_check CHECK (
        status IN ('PROPOSED','APPROVED','REJECTED','COMPLETION_SUBMITTED','ALLOCATED')
    ),
    CONSTRAINT performance_activity_counts_check CHECK (
        expected_participants >= 0
        AND (actual_participants IS NULL OR actual_participants >= 0)
        AND (verified_participants IS NULL OR verified_participants >= 0)
    ),
    CONSTRAINT performance_activity_points_check CHECK (
        total_points IS NULL OR (total_points >= 0 AND total_points * 2 = trunc(total_points * 2))
    )
);
CREATE INDEX performance_activity_status_idx
    ON performance_activities (organization_id, status, event_date DESC);

CREATE TABLE performance_activity_allocations (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    activity_id uuid NOT NULL REFERENCES performance_activities(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    points numeric(10,1) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT performance_activity_allocation_unique UNIQUE (activity_id, membership_id),
    CONSTRAINT performance_activity_allocation_points CHECK (points >= 0 AND points * 2 = trunc(points * 2))
);

CREATE TABLE performance_manual_entries (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    performance_date date NOT NULL,
    entry_type varchar(20) NOT NULL,
    title varchar(200) NOT NULL,
    reason varchar(2000) NOT NULL,
    points numeric(10,1) NOT NULL,
    related_ledger_id uuid,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT performance_manual_entry_type_check CHECK (entry_type IN ('LOGISTICS','CORRECTION')),
    CONSTRAINT performance_manual_entry_points_check CHECK (
        points * 2 = trunc(points * 2) AND (entry_type='CORRECTION' OR points >= 0)
    )
);

CREATE TABLE performance_evidence (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    owner_type varchar(20) NOT NULL,
    owner_id uuid NOT NULL,
    uploaded_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    file_name varchar(255) NOT NULL,
    content_type varchar(80) NOT NULL,
    file_size bigint NOT NULL,
    content bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT performance_evidence_owner_type_check CHECK (owner_type IN ('INTERACTION','ACTIVITY','MANUAL')),
    CONSTRAINT performance_evidence_size_check CHECK (file_size > 0 AND file_size <= 5242880)
);
CREATE INDEX performance_evidence_owner_idx
    ON performance_evidence (organization_id, owner_type, owner_id, created_at);

CREATE TABLE performance_score_ledger (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    performance_date date NOT NULL,
    category varchar(20) NOT NULL,
    source_id uuid NOT NULL,
    points numeric(10,1) NOT NULL,
    description varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT performance_ledger_category_check CHECK (category IN ('INTERACTION','ACTIVITY','LOGISTICS','CORRECTION')),
    CONSTRAINT performance_ledger_points_check CHECK (points * 2 = trunc(points * 2)),
    CONSTRAINT performance_ledger_source_unique UNIQUE (organization_id, category, source_id, membership_id)
);
CREATE INDEX performance_ledger_member_date_idx
    ON performance_score_ledger (organization_id, membership_id, performance_date DESC);
CREATE INDEX performance_ledger_team_date_idx
    ON performance_score_ledger (organization_id, team_id_snapshot, performance_date DESC);

ALTER TABLE performance_manual_entries
    ADD CONSTRAINT performance_manual_related_ledger_fk
    FOREIGN KEY (related_ledger_id) REFERENCES performance_score_ledger(id) ON DELETE SET NULL;

CREATE TABLE performance_days (
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    performance_date date NOT NULL,
    rule_version_id uuid NOT NULL REFERENCES performance_rule_versions(id),
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    reopened_reason varchar(2000),
    settled_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    settled_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, performance_date),
    CONSTRAINT performance_day_status_check CHECK (status IN ('OPEN','REVIEWING','SETTLED'))
);

CREATE TABLE performance_daily_settlements (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    performance_date date NOT NULL,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    team_id_snapshot uuid REFERENCES teams(id) ON DELETE SET NULL,
    rule_version_id uuid NOT NULL REFERENCES performance_rule_versions(id),
    baseline numeric(10,1) NOT NULL,
    business_points numeric(10,1) NOT NULL,
    logistics_points numeric(10,1) NOT NULL,
    correction_points numeric(10,1) NOT NULL,
    earned_points numeric(10,1) NOT NULL,
    shortfall numeric(10,1) NOT NULL,
    deduction numeric(10,1) NOT NULL,
    final_points numeric(10,1) NOT NULL,
    settled_at timestamptz NOT NULL,
    settlement_revision integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    reopened_at timestamptz,
    reopened_reason varchar(2000),
    CONSTRAINT performance_settlement_revision_check CHECK (settlement_revision > 0),
    CONSTRAINT performance_settlement_unique UNIQUE (
        organization_id, performance_date, membership_id, settlement_revision
    )
);
CREATE INDEX performance_settlement_member_date_idx
    ON performance_daily_settlements (organization_id, membership_id, performance_date DESC);
CREATE UNIQUE INDEX performance_settlement_active_unique
    ON performance_daily_settlements (organization_id, performance_date, membership_id)
    WHERE active;
