ALTER TABLE customers
    ADD COLUMN lead_review_status varchar(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN lead_review_reason varchar(1000),
    ADD COLUMN lead_reviewed_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    ADD COLUMN lead_reviewed_at timestamptz;

ALTER TABLE customers
    ADD CONSTRAINT customers_lead_review_status_check
        CHECK (lead_review_status IN ('PENDING', 'VALID', 'INVALID'));

CREATE INDEX customers_lead_review_queue_idx
    ON customers (organization_id, lead_review_status, created_at DESC)
    WHERE archived_at IS NULL;

CREATE INDEX customers_creator_review_date_idx
    ON customers (organization_id, created_by_membership_id, lead_review_status, created_at DESC)
    WHERE archived_at IS NULL;
