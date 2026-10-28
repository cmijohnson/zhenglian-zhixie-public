CREATE TABLE mobile_push_registrations (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES mobile_device_sessions(id) ON DELETE CASCADE,
    provider varchar(16) NOT NULL,
    vendor varchar(80),
    token_hash varchar(64) NOT NULL,
    token_ciphertext bytea NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    invalidated_at timestamptz,
    CONSTRAINT mobile_push_provider_check CHECK (provider IN ('UNIPUSH')),
    CONSTRAINT mobile_push_token_unique UNIQUE (provider, token_hash),
    CONSTRAINT mobile_push_session_unique UNIQUE (session_id, provider)
);

CREATE TABLE mobile_push_preferences (
    membership_id uuid PRIMARY KEY REFERENCES memberships(id) ON DELETE CASCADE,
    urgent_reminders boolean NOT NULL DEFAULT true,
    tasks boolean NOT NULL DEFAULT true,
    consultations boolean NOT NULL DEFAULT true,
    announcements boolean NOT NULL DEFAULT true,
    releases boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE mobile_push_outbox (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    recipient_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    event_type varchar(80) NOT NULL,
    target_path varchar(500) NOT NULL,
    payload jsonb NOT NULL,
    dedupe_key varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    available_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    locked_at timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mobile_push_outbox_status_check CHECK (status IN ('PENDING','PROCESSING','DELIVERED','FAILED','EXPIRED')),
    CONSTRAINT mobile_push_outbox_attempt_check CHECK (attempt_count >= 0 AND max_attempts BETWEEN 1 AND 5),
    CONSTRAINT mobile_push_outbox_unique UNIQUE (organization_id,recipient_membership_id,dedupe_key)
);
CREATE INDEX mobile_push_outbox_dispatch_idx
    ON mobile_push_outbox (status,available_at,created_at) WHERE status IN ('PENDING','PROCESSING');

CREATE TABLE mobile_push_deliveries (
    id uuid PRIMARY KEY,
    outbox_id uuid NOT NULL REFERENCES mobile_push_outbox(id) ON DELETE CASCADE,
    registration_id uuid REFERENCES mobile_push_registrations(id) ON DELETE SET NULL,
    provider varchar(16) NOT NULL,
    attempt_number integer NOT NULL,
    status varchar(24) NOT NULL,
    provider_message_id varchar(200),
    failure_code varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT mobile_push_delivery_provider_check CHECK (provider IN ('UNIPUSH')),
    CONSTRAINT mobile_push_delivery_status_check CHECK (status IN ('ATTEMPTED','DELIVERED','RETRYABLE_FAILURE','PERMANENT_FAILURE')),
    CONSTRAINT mobile_push_delivery_attempt_unique UNIQUE (outbox_id,registration_id,attempt_number)
);
CREATE INDEX mobile_push_delivery_outbox_idx ON mobile_push_deliveries (outbox_id,created_at);

CREATE TABLE mobile_webview_tickets (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES mobile_device_sessions(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    ticket_hash varchar(64) NOT NULL UNIQUE,
    target varchar(80) NOT NULL,
    target_path varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX mobile_webview_ticket_expiry_idx ON mobile_webview_tickets (expires_at, used_at);

CREATE TABLE mobile_app_releases (
    id uuid PRIMARY KEY,
    platform varchar(16) NOT NULL,
    version_name varchar(40) NOT NULL,
    version_code bigint NOT NULL,
    minimum_supported_version_code bigint NOT NULL,
    mandatory boolean NOT NULL DEFAULT false,
    notes text NOT NULL,
    apk_path varchar(1000),
    apk_size bigint,
    apk_sha256 varchar(64),
    manifest_signature text,
    published boolean NOT NULL DEFAULT false,
    released_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mobile_release_platform_check CHECK (platform IN ('ANDROID','IOS')),
    CONSTRAINT mobile_release_build_check CHECK (
        version_code >= 1 AND minimum_supported_version_code >= 1
    ),
    CONSTRAINT mobile_release_apk_check CHECK (
        (platform='IOS' AND apk_path IS NULL AND apk_size IS NULL AND apk_sha256 IS NULL)
        OR platform='ANDROID'
    ),
    CONSTRAINT mobile_release_hash_check CHECK (apk_sha256 IS NULL OR apk_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT mobile_release_build_unique UNIQUE (platform, version_code)
);
CREATE INDEX mobile_app_release_current_idx
    ON mobile_app_releases (platform, published, version_code DESC);
