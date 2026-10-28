CREATE TABLE customer_deal_requests (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    requested_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    source varchar(32) NOT NULL DEFAULT 'DIRECT',
    legacy_business_cycle_id uuid REFERENCES customer_business_cycles(id) ON DELETE SET NULL,
    decided_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    idempotency_key_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT customer_deal_request_status_check CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT customer_deal_request_source_check CHECK (source IN ('DIRECT','LEGACY_BUSINESS_CYCLE')),
    CONSTRAINT customer_deal_request_idempotency_unique UNIQUE (
        organization_id, requested_by_membership_id, customer_id, idempotency_key_hash
    )
);
CREATE UNIQUE INDEX uq_customer_deal_request_pending
    ON customer_deal_requests (organization_id, customer_id) WHERE status='PENDING';
CREATE UNIQUE INDEX uq_customer_deal_request_approved
    ON customer_deal_requests (organization_id, customer_id) WHERE status='APPROVED';
CREATE INDEX customer_deal_request_admin_idx
    ON customer_deal_requests (organization_id, status, requested_at DESC);

CREATE TABLE customer_deal_request_events (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    request_id uuid NOT NULL REFERENCES customer_deal_requests(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    actor_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    event_type varchar(24) NOT NULL,
    previous_status varchar(16),
    new_status varchar(16) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX customer_deal_request_event_idx
    ON customer_deal_request_events (organization_id, request_id, created_at, id);

CREATE OR REPLACE FUNCTION reject_customer_deal_request_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'customer deal request event history is append-only';
END;
$$;
CREATE TRIGGER customer_deal_request_events_append_only
BEFORE UPDATE OR DELETE ON customer_deal_request_events
FOR EACH ROW EXECUTE FUNCTION reject_customer_deal_request_event_mutation();

INSERT INTO customer_deal_requests (
    id, organization_id, customer_id, requested_by_membership_id, status, source,
    legacy_business_cycle_id, decided_by_membership_id, requested_at, decided_at,
    idempotency_key_hash, created_at, updated_at, version
)
SELECT gen_random_uuid(), cycle.organization_id, cycle.customer_id,
       coalesce(cycle.won_declared_by_membership_id, cycle.created_by_membership_id),
       'APPROVED', 'LEGACY_BUSINESS_CYCLE', cycle.id,
       cycle.won_confirmed_by_membership_id,
       coalesce(cycle.won_declared_at, cycle.created_at),
       coalesce(cycle.won_confirmed_at, cycle.updated_at),
       md5('legacy-won:' || cycle.id::text) || md5(cycle.id::text || ':legacy-won'),
       cycle.created_at, cycle.updated_at, cycle.version
FROM customer_business_cycles cycle
WHERE cycle.status='WON'
  AND NOT EXISTS (
      SELECT 1 FROM customer_business_cycles newer
      WHERE newer.organization_id=cycle.organization_id AND newer.customer_id=cycle.customer_id
        AND newer.status='WON'
        AND (coalesce(newer.won_confirmed_at, newer.updated_at), newer.id)
            > (coalesce(cycle.won_confirmed_at, cycle.updated_at), cycle.id)
  )
ON CONFLICT DO NOTHING;

INSERT INTO customer_deal_requests (
    id, organization_id, customer_id, requested_by_membership_id, status, source,
    legacy_business_cycle_id, requested_at, idempotency_key_hash, created_at, updated_at, version
)
SELECT gen_random_uuid(), cycle.organization_id, cycle.customer_id,
       coalesce(cycle.won_declared_by_membership_id, cycle.created_by_membership_id),
       'PENDING', 'LEGACY_BUSINESS_CYCLE', cycle.id,
       coalesce(cycle.won_declared_at, cycle.created_at),
       md5('legacy-won-pending:' || cycle.id::text) || md5(cycle.id::text || ':legacy-won-pending'),
       cycle.created_at, cycle.updated_at, cycle.version
FROM customer_business_cycles cycle
WHERE cycle.status='WON_PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM customer_deal_requests request
      WHERE request.organization_id=cycle.organization_id AND request.customer_id=cycle.customer_id
        AND request.status IN ('PENDING','APPROVED')
  )
ON CONFLICT DO NOTHING;
