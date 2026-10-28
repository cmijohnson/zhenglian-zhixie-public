ALTER TABLE user_accounts
    ADD COLUMN auth_version bigint NOT NULL DEFAULT 0;

CREATE TABLE oauth_clients (
    id uuid PRIMARY KEY,
    client_id varchar(160) NOT NULL UNIQUE,
    client_name varchar(200) NOT NULL,
    redirect_uris jsonb NOT NULL,
    grant_types jsonb NOT NULL,
    response_types jsonb NOT NULL,
    token_endpoint_auth_method varchar(40) NOT NULL DEFAULT 'none',
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);

CREATE TABLE oauth_authorization_requests (
    id uuid PRIMARY KEY,
    client_id varchar(160) NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
    redirect_uri varchar(1000) NOT NULL,
    state varchar(1000),
    code_challenge varchar(160) NOT NULL,
    resource_uri varchar(1000) NOT NULL,
    requested_scopes jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED')),
    user_id uuid REFERENCES user_accounts(id) ON DELETE CASCADE,
    membership_id uuid REFERENCES memberships(id) ON DELETE CASCADE,
    organization_id uuid REFERENCES organizations(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    decided_at timestamptz
);
CREATE INDEX idx_oauth_request_expiry ON oauth_authorization_requests (expires_at);

CREATE TABLE oauth_grants (
    id uuid PRIMARY KEY,
    client_id varchar(160) NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    resource_uri varchar(1000) NOT NULL,
    scopes jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz
);
CREATE INDEX idx_oauth_grant_member ON oauth_grants (membership_id, status);
CREATE INDEX idx_oauth_grant_org ON oauth_grants (organization_id, status);

CREATE TABLE oauth_authorization_codes (
    id uuid PRIMARY KEY,
    code_hash char(64) NOT NULL UNIQUE,
    authorization_request_id uuid NOT NULL REFERENCES oauth_authorization_requests(id) ON DELETE CASCADE,
    grant_id uuid NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    redirect_uri varchar(1000) NOT NULL,
    code_challenge varchar(160) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX idx_oauth_code_expiry ON oauth_authorization_codes (expires_at);

CREATE TABLE oauth_access_tokens (
    id uuid PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE,
    grant_id uuid NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    user_auth_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz
);
CREATE INDEX idx_oauth_access_expiry ON oauth_access_tokens (expires_at) WHERE revoked_at IS NULL;

CREATE TABLE oauth_refresh_tokens (
    id uuid PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE,
    grant_id uuid NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    user_auth_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    replaced_by_token_id uuid REFERENCES oauth_refresh_tokens(id)
);
CREATE INDEX idx_oauth_refresh_expiry ON oauth_refresh_tokens (expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE mcp_idempotency_records (
    id uuid PRIMARY KEY,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    client_id varchar(160) NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
    tool_name varchar(120) NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    arguments_hash char(64) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING', 'SUCCESS')),
    result jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT uq_mcp_idempotency UNIQUE (membership_id, client_id, tool_name, idempotency_key)
);
CREATE INDEX idx_mcp_idempotency_expiry ON mcp_idempotency_records (expires_at);

CREATE TABLE mcp_approval_requests (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    client_id varchar(160) NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
    tool_name varchar(120) NOT NULL,
    arguments_hash char(64) NOT NULL,
    summary varchar(1000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CONSUMED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    decided_at timestamptz,
    consumed_at timestamptz
);
CREATE INDEX idx_mcp_approval_member ON mcp_approval_requests (membership_id, created_at DESC);
CREATE INDEX idx_mcp_approval_expiry ON mcp_approval_requests (expires_at);
