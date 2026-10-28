\set ON_ERROR_STOP on
\pset pager off

WITH actual AS (
    SELECT 'organizations' AS metric, count(*)::bigint AS value FROM organizations
    UNION ALL SELECT 'teams', count(*) FROM teams
    UNION ALL SELECT 'accounts', count(*) FROM user_accounts
    UNION ALL SELECT 'customers', count(*) FROM customers
    UNION ALL SELECT 'interactions', count(*) FROM interactions
    UNION ALL SELECT 'reminders', count(*) FROM reminders
    UNION ALL SELECT 'tasks', count(*) FROM work_tasks
    UNION ALL SELECT 'content_posts', count(*) FROM content_posts
    UNION ALL SELECT 'daily_reports', count(*) FROM daily_reports
    UNION ALL SELECT 'announcements', count(*) FROM announcements
    UNION ALL SELECT 'dictionary_items', count(*) FROM dictionary_items
    UNION ALL SELECT 'tags', count(*) FROM tags
), expected(metric, value) AS (
    VALUES ('organizations', 2::bigint), ('teams', 8), ('accounts', 113),
           ('customers', 51000), ('interactions', 250000), ('reminders', 40000),
           ('tasks', 60), ('content_posts', 6660), ('daily_reports', 3330),
           ('announcements', 100), ('dictionary_items', 60), ('tags', 40)
)
SELECT e.metric, e.value AS expected, a.value AS actual,
       CASE WHEN e.value = a.value THEN 'PASS' ELSE 'FAIL' END AS status
FROM expected e JOIN actual a USING (metric)
ORDER BY e.metric;

SELECT 'cross_organization_customer_owner' AS invariant, count(*) AS violations
FROM customers c JOIN memberships m ON m.id = c.owner_membership_id
WHERE c.organization_id <> m.organization_id
UNION ALL
SELECT 'cross_organization_customer_creator', count(*)
FROM customers c JOIN memberships m ON m.id = c.created_by_membership_id
WHERE c.organization_id <> m.organization_id
UNION ALL
SELECT 'archived_customer_pending_reminders', count(*)
FROM reminders r JOIN customers c ON c.id = r.customer_id
WHERE c.archived_at IS NOT NULL AND r.status = 'PENDING'
UNION ALL
SELECT 'duplicate_task_assignment', count(*) FROM (
    SELECT task_id, membership_id FROM work_task_assignments
    GROUP BY task_id, membership_id HAVING count(*) > 1
) duplicate_rows
UNION ALL
SELECT 'content_daily_platform_limit', count(*) FROM (
    SELECT organization_id, membership_id, platform, publish_date FROM content_posts
    GROUP BY organization_id, membership_id, platform, publish_date HAVING count(*) > 1
) duplicate_rows
UNION ALL
SELECT 'team_multiple_leaders', count(*) FROM (
    SELECT team_id FROM team_members WHERE is_manager GROUP BY team_id HAVING count(*) > 1
) duplicate_rows
UNION ALL
SELECT 'orphan_interactions', count(*) FROM interactions i LEFT JOIN customers c ON c.id=i.customer_id WHERE c.id IS NULL
UNION ALL
SELECT 'orphan_reminders', count(*) FROM reminders r LEFT JOIN customers c ON c.id=r.customer_id WHERE c.id IS NULL;

SELECT o.code,
       (SELECT count(*) FROM teams t WHERE t.organization_id=o.id) AS teams,
       (SELECT count(*) FROM memberships m WHERE m.organization_id=o.id) AS accounts,
       (SELECT count(*) FROM customers c WHERE c.organization_id=o.id) AS customers
FROM organizations o
ORDER BY o.code;
