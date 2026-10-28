CREATE TABLE mobile_device_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    installation_id varchar(128) NOT NULL,
    platform varchar(16) NOT NULL,
    device_name varchar(160),
    app_version varchar(40) NOT NULL,
    build_number bigint NOT NULL,
    locale varchar(32),
    timezone varchar(64),
    refresh_family_id uuid NOT NULL,
    user_auth_version bigint NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CONSTRAINT mobile_device_platform_check CHECK (platform IN ('ANDROID','IOS')),
    CONSTRAINT mobile_device_status_check CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT mobile_device_build_check CHECK (build_number >= 0),
    CONSTRAINT mobile_device_installation_unique UNIQUE (membership_id, installation_id)
);
CREATE INDEX mobile_device_session_member_idx
    ON mobile_device_sessions (membership_id, status, last_seen_at DESC);

CREATE TABLE mobile_access_tokens (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES mobile_device_sessions(id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz
);
CREATE INDEX mobile_access_token_session_idx ON mobile_access_tokens (session_id, expires_at DESC);

CREATE TABLE mobile_refresh_tokens (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES mobile_device_sessions(id) ON DELETE CASCADE,
    family_id uuid NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    replaced_by_token_id uuid REFERENCES mobile_refresh_tokens(id) ON DELETE SET NULL
);
CREATE INDEX mobile_refresh_token_family_idx
    ON mobile_refresh_tokens (session_id, family_id, created_at DESC);
