ALTER TABLE assistant_runs
    ALTER COLUMN actor_membership_id DROP NOT NULL;

CREATE INDEX idx_assistant_runs_customer_intelligence
    ON assistant_runs (organization_id, customer_id, created_at DESC)
    WHERE purpose = 'CUSTOMER_INTELLIGENCE';
