CREATE TABLE announcements (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    author_membership_id uuid NOT NULL REFERENCES memberships(id),
    title varchar(200) NOT NULL,
    content varchar(10000) NOT NULL,
    priority varchar(20) NOT NULL DEFAULT 'NORMAL',
    pinned boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL DEFAULT 'PUBLISHED',
    published_at timestamptz NOT NULL,
    archived_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT announcements_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT announcements_content_not_blank CHECK (btrim(content) <> ''),
    CONSTRAINT announcements_priority_check CHECK (priority IN ('NORMAL', 'IMPORTANT', 'URGENT')),
    CONSTRAINT announcements_status_check CHECK (status IN ('PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX announcements_feed_idx
    ON announcements (organization_id, status, pinned DESC, priority DESC, published_at DESC);

CREATE INDEX content_posts_sender_filter_idx
    ON content_posts (organization_id, membership_id, publish_date DESC);
