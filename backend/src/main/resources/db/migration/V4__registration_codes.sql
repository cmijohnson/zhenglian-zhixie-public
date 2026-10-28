CREATE TABLE registration_codes (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    code_hash varchar(64) NOT NULL,
    code_hint varchar(40) NOT NULL,
    max_uses integer NOT NULL,
    used_count integer NOT NULL DEFAULT 0,
    expires_at timestamptz,
    active boolean NOT NULL DEFAULT true,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL,
    disabled_at timestamptz,
    last_used_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT registration_codes_hash_unique UNIQUE (code_hash),
    CONSTRAINT registration_codes_max_uses_check CHECK (max_uses BETWEEN 1 AND 1000),
    CONSTRAINT registration_codes_usage_check CHECK (used_count BETWEEN 0 AND max_uses)
);

CREATE INDEX registration_codes_org_created_idx
    ON registration_codes (organization_id, created_at DESC);
CREATE INDEX registration_codes_org_active_idx
    ON registration_codes (organization_id, active, expires_at);
