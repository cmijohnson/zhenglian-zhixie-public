CREATE TABLE content_directions (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    title varchar(160) NOT NULL,
    description varchar(4000),
    active boolean NOT NULL DEFAULT true,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT content_directions_title_not_blank CHECK (btrim(title) <> '')
);

CREATE INDEX content_directions_org_active_idx
    ON content_directions (organization_id, active, created_at DESC);

CREATE TABLE content_posts (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    membership_id uuid NOT NULL REFERENCES memberships(id),
    platform varchar(32) NOT NULL,
    publish_date date NOT NULL,
    direction_id uuid NOT NULL REFERENCES content_directions(id),
    topic varchar(240) NOT NULL,
    content_url varchar(1000) NOT NULL,
    view_count bigint NOT NULL DEFAULT 0,
    like_count bigint NOT NULL DEFAULT 0,
    favorite_count bigint NOT NULL DEFAULT 0,
    original_declared boolean NOT NULL,
    review_status varchar(24) NOT NULL DEFAULT 'SUBMITTED',
    review_reason varchar(1000),
    reviewed_by_membership_id uuid REFERENCES memberships(id),
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT content_posts_platform_check CHECK (platform IN ('XIAOHONGSHU', 'DOUYIN')),
    CONSTRAINT content_posts_review_status_check CHECK (review_status IN ('SUBMITTED', 'VALID', 'INVALID')),
    CONSTRAINT content_posts_topic_not_blank CHECK (btrim(topic) <> ''),
    CONSTRAINT content_posts_url_not_blank CHECK (btrim(content_url) <> ''),
    CONSTRAINT content_posts_metrics_non_negative CHECK (
        view_count >= 0 AND like_count >= 0 AND favorite_count >= 0
    ),
    CONSTRAINT content_posts_one_platform_per_day UNIQUE (
        organization_id, membership_id, platform, publish_date
    )
);

CREATE INDEX content_posts_circle_idx
    ON content_posts (organization_id, publish_date DESC, created_at DESC);
CREATE INDEX content_posts_member_progress_idx
    ON content_posts (organization_id, membership_id, review_status, platform, publish_date);

CREATE TABLE daily_reports (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    membership_id uuid NOT NULL REFERENCES memberships(id),
    report_date date NOT NULL,
    deep_communication_count integer NOT NULL DEFAULT 0,
    today_issues varchar(5000) NOT NULL,
    tomorrow_plan varchar(5000) NOT NULL,
    submitted_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT daily_reports_deep_count_non_negative CHECK (deep_communication_count >= 0),
    CONSTRAINT daily_reports_issues_not_blank CHECK (btrim(today_issues) <> ''),
    CONSTRAINT daily_reports_plan_not_blank CHECK (btrim(tomorrow_plan) <> ''),
    CONSTRAINT daily_reports_member_day_unique UNIQUE (organization_id, membership_id, report_date)
);

CREATE INDEX daily_reports_org_date_idx
    ON daily_reports (organization_id, report_date DESC, membership_id);

CREATE TABLE work_tasks (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    title varchar(200) NOT NULL,
    description varchar(6000) NOT NULL,
    distribution_type varchar(24) NOT NULL,
    start_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    max_claimants integer,
    status varchar(20) NOT NULL DEFAULT 'PUBLISHED',
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT work_tasks_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT work_tasks_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT work_tasks_distribution_check CHECK (
        distribution_type IN ('MANDATORY', 'ASSIGNED', 'SELF_CLAIM')
    ),
    CONSTRAINT work_tasks_status_check CHECK (status IN ('PUBLISHED', 'CLOSED')),
    CONSTRAINT work_tasks_date_order_check CHECK (due_at > start_at),
    CONSTRAINT work_tasks_claim_limit_check CHECK (max_claimants IS NULL OR max_claimants > 0)
);

CREATE INDEX work_tasks_org_due_idx
    ON work_tasks (organization_id, status, due_at);

CREATE TABLE work_task_assignments (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    task_id uuid NOT NULL REFERENCES work_tasks(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES memberships(id),
    assignment_source varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    result_summary varchar(5000),
    evidence_url varchar(1000),
    review_feedback varchar(2000),
    claimed_at timestamptz,
    submitted_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT work_task_assignments_source_check CHECK (
        assignment_source IN ('MANDATORY', 'ASSIGNED', 'CLAIMED')
    ),
    CONSTRAINT work_task_assignments_status_check CHECK (
        status IN ('PENDING', 'IN_PROGRESS', 'SUBMITTED', 'COMPLETED', 'RETURNED')
    ),
    CONSTRAINT work_task_assignments_member_unique UNIQUE (task_id, membership_id)
);

CREATE INDEX work_task_assignments_member_idx
    ON work_task_assignments (organization_id, membership_id, status, updated_at DESC);
CREATE INDEX work_task_assignments_review_idx
    ON work_task_assignments (organization_id, task_id, status);
