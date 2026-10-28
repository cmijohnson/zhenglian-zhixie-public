CREATE TABLE system_notifications (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    recipient_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    event_type varchar(80) NOT NULL,
    title varchar(200) NOT NULL,
    summary varchar(1000) NOT NULL,
    target_path varchar(500),
    dedupe_key varchar(200) NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '90 days'),
    CONSTRAINT system_notification_dedupe_unique
        UNIQUE (organization_id, recipient_membership_id, dedupe_key)
);
CREATE INDEX system_notifications_inbox_idx
    ON system_notifications (organization_id, recipient_membership_id, read_at, created_at DESC);

CREATE TABLE wecom_webhook_channels (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    encrypted_url bytea NOT NULL,
    event_types jsonb NOT NULL DEFAULT '[]'::jsonb,
    active boolean NOT NULL DEFAULT true,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT wecom_webhook_channel_name_unique UNIQUE (organization_id, name)
);

CREATE TABLE wecom_webhook_deliveries (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    channel_id uuid NOT NULL REFERENCES wecom_webhook_channels(id) ON DELETE CASCADE,
    event_type varchar(80) NOT NULL,
    title varchar(200) NOT NULL,
    redacted_summary varchar(1000) NOT NULL,
    target_path varchar(500),
    dedupe_key varchar(200) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error_code varchar(100),
    delivered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT wecom_delivery_status_check
        CHECK (status IN ('PENDING','SENDING','RETRYING','DELIVERED','FAILED')),
    CONSTRAINT wecom_delivery_attempt_check CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT wecom_delivery_dedupe_unique UNIQUE (channel_id, dedupe_key)
);
CREATE INDEX wecom_webhook_delivery_queue_idx
    ON wecom_webhook_deliveries (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING','RETRYING');

CREATE OR REPLACE FUNCTION reject_append_only_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only table % cannot be updated or deleted', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS audit_logs_append_only ON audit_logs;
CREATE TRIGGER audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
