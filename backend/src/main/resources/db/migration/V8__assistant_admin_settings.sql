CREATE TABLE assistant_settings (
    organization_id UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model VARCHAR(200) NOT NULL,
    encrypted_api_key TEXT,
    updated_by_membership_id UUID NOT NULL REFERENCES memberships(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_assistant_settings_updated_at ON assistant_settings(updated_at DESC);

