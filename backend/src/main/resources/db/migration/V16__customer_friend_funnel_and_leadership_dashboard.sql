ALTER TABLE customers
    ADD COLUMN friend_added_at timestamptz,
    ADD COLUMN friend_trusted_at timestamptz,
    ADD COLUMN friend_qualified_at timestamptz;

ALTER TABLE customers
    ADD CONSTRAINT customers_friend_trusted_requires_added
        CHECK (friend_trusted_at IS NULL OR friend_added_at IS NOT NULL),
    ADD CONSTRAINT customers_friend_qualified_requires_trusted
        CHECK (friend_qualified_at IS NULL OR friend_trusted_at IS NOT NULL),
    ADD CONSTRAINT customers_friend_trusted_after_added
        CHECK (friend_trusted_at IS NULL OR friend_trusted_at >= friend_added_at),
    ADD CONSTRAINT customers_friend_qualified_after_trusted
        CHECK (friend_qualified_at IS NULL OR friend_qualified_at >= friend_trusted_at);

CREATE INDEX customers_friend_added_dashboard_idx
    ON customers (organization_id, friend_added_at DESC)
    WHERE friend_added_at IS NOT NULL AND archived_at IS NULL;

CREATE INDEX customers_friend_trusted_dashboard_idx
    ON customers (organization_id, friend_trusted_at DESC)
    WHERE friend_trusted_at IS NOT NULL AND archived_at IS NULL;

CREATE INDEX customers_friend_qualified_dashboard_idx
    ON customers (organization_id, friend_qualified_at DESC)
    WHERE friend_qualified_at IS NOT NULL AND archived_at IS NULL;
