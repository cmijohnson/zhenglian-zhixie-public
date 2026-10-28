CREATE TABLE forum_boards (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name varchar(80) NOT NULL,
    slug varchar(80) NOT NULL,
    description varchar(240),
    icon varchar(24) NOT NULL DEFAULT '💬',
    color varchar(16) NOT NULL DEFAULT '#3157d5',
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_forum_board_org_slug UNIQUE (organization_id, slug),
    CONSTRAINT uq_forum_board_org_name UNIQUE (organization_id, name)
);

CREATE OR REPLACE FUNCTION seed_forum_boards_for_organization()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO forum_boards (id, organization_id, name, slug, description, icon, color, sort_order)
    VALUES
        (gen_random_uuid(), NEW.id, '经验交流', 'sharing', '分享工作经验、方法和灵感', '✦', '#3157d5', 10),
        (gen_random_uuid(), NEW.id, '问题讨论', 'discussion', '提出问题，与团队共同寻找答案', '？', '#0b8d69', 20),
        (gen_random_uuid(), NEW.id, '资源共享', 'resources', '沉淀文档、工具和优质资源', '↗', '#d17a15', 30)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_seed_forum_boards
AFTER INSERT ON organizations
FOR EACH ROW EXECUTE FUNCTION seed_forum_boards_for_organization();

INSERT INTO forum_boards (id, organization_id, name, slug, description, icon, color, sort_order)
SELECT gen_random_uuid(), id, '经验交流', 'sharing', '分享工作经验、方法和灵感', '✦', '#3157d5', 10
FROM organizations;
INSERT INTO forum_boards (id, organization_id, name, slug, description, icon, color, sort_order)
SELECT gen_random_uuid(), id, '问题讨论', 'discussion', '提出问题，与团队共同寻找答案', '？', '#0b8d69', 20
FROM organizations;
INSERT INTO forum_boards (id, organization_id, name, slug, description, icon, color, sort_order)
SELECT gen_random_uuid(), id, '资源共享', 'resources', '沉淀文档、工具和优质资源', '↗', '#d17a15', 30
FROM organizations;

CREATE TABLE forum_posts (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    board_id uuid NOT NULL REFERENCES forum_boards(id),
    author_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    author_display_name varchar(120) NOT NULL,
    post_type varchar(24) NOT NULL DEFAULT 'POST' CHECK (post_type IN ('POST', 'ANNOUNCEMENT')),
    title varchar(180) NOT NULL,
    content text NOT NULL,
    excerpt varchar(360),
    status varchar(24) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED')),
    pinned boolean NOT NULL DEFAULT false,
    locked boolean NOT NULL DEFAULT false,
    view_count bigint NOT NULL DEFAULT 0,
    legacy_source varchar(40),
    legacy_id varchar(120),
    published_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_forum_post_legacy ON forum_posts (legacy_source, legacy_id)
WHERE legacy_source IS NOT NULL AND legacy_id IS NOT NULL;
CREATE INDEX idx_forum_post_public ON forum_posts (organization_id, pinned DESC, published_at DESC)
WHERE status = 'PUBLISHED';
CREATE INDEX idx_forum_post_board ON forum_posts (board_id, published_at DESC)
WHERE status = 'PUBLISHED';

CREATE TABLE forum_post_tags (
    post_id uuid NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
    tag varchar(48) NOT NULL,
    PRIMARY KEY (post_id, tag)
);
CREATE INDEX idx_forum_tag_name ON forum_post_tags (tag);

CREATE TABLE forum_attachments (
    id uuid PRIMARY KEY,
    post_id uuid NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
    file_name varchar(255) NOT NULL,
    content_type varchar(120) NOT NULL,
    file_size bigint NOT NULL CHECK (file_size >= 0 AND file_size <= 26214400),
    content bytea,
    external_url text,
    legacy_source varchar(40),
    legacy_id varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_forum_attachment_source CHECK (
        (content IS NOT NULL AND external_url IS NULL) OR
        (content IS NULL AND external_url IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_forum_attachment_legacy ON forum_attachments (legacy_source, legacy_id)
WHERE legacy_source IS NOT NULL AND legacy_id IS NOT NULL;

CREATE TABLE forum_comments (
    id uuid PRIMARY KEY,
    post_id uuid NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
    author_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    author_display_name varchar(120) NOT NULL,
    parent_id uuid REFERENCES forum_comments(id) ON DELETE SET NULL,
    content text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED')),
    legacy_source varchar(40),
    legacy_id varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_forum_comment_post ON forum_comments (post_id, created_at)
WHERE status = 'PUBLISHED';
CREATE UNIQUE INDEX uq_forum_comment_legacy ON forum_comments (legacy_source, legacy_id)
WHERE legacy_source IS NOT NULL AND legacy_id IS NOT NULL;

CREATE TABLE forum_likes (
    post_id uuid NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, membership_id)
);

CREATE TABLE forum_announcement_reads (
    post_id uuid NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    read_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, membership_id)
);

CREATE TABLE forum_reports (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    post_id uuid NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
    reporter_membership_id uuid NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    reason varchar(500) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    resolved_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    resolution_note varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);
CREATE UNIQUE INDEX uq_forum_open_report ON forum_reports (post_id, reporter_membership_id)
WHERE status = 'OPEN';

CREATE TABLE forum_moderation_actions (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    post_id uuid REFERENCES forum_posts(id) ON DELETE SET NULL,
    actor_membership_id uuid NOT NULL REFERENCES memberships(id),
    action varchar(40) NOT NULL,
    reason varchar(500),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_forum_moderation_org_created
ON forum_moderation_actions (organization_id, created_at DESC);
