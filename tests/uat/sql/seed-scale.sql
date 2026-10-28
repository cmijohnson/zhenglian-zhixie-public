\set ON_ERROR_STOP on
\pset pager off

-- Deterministic formal-scale UAT data. Run only against the isolated UAT database.
CREATE TABLE IF NOT EXISTS uat_seed_manifest (
    seed bigint PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now(),
    manifest jsonb NOT NULL
);

SELECT EXISTS (SELECT 1 FROM uat_seed_manifest WHERE seed = 20260720) AS seed_exists \gset
\if :seed_exists
\echo 'seed 20260720 already applied; leaving existing UAT data unchanged'
\quit
\endif

BEGIN;

CREATE TEMP TABLE uat_context AS
SELECT o.id AS main_org_id,
       m.id AS main_admin_membership_id,
       u.password_hash AS shared_password_hash
FROM organizations o
JOIN memberships m ON m.organization_id = o.id
JOIN user_accounts u ON u.id = m.user_id
JOIN membership_roles mr ON mr.membership_id = m.id
WHERE o.code = 'yingge'
  AND mr.role_id = '00000000-0000-0000-0000-000000000001'::uuid
ORDER BY m.created_at
LIMIT 1;

DO $$
BEGIN
    IF (SELECT count(*) FROM uat_context) <> 1 THEN
        RAISE EXCEPTION 'expected exactly one bootstrap UAT organization and administrator';
    END IF;
END $$;

INSERT INTO organizations (id, name, code, status, created_at, updated_at)
VALUES (md5('uat20260720:org:tenant-x')::uuid, 'UAT-20260720-隔离组织',
        'uat-tenant-x', 'ACTIVE', now(), now());

INSERT INTO organization_settings (organization_id, timezone, default_reminder_time)
VALUES (md5('uat20260720:org:tenant-x')::uuid, 'Asia/Shanghai', '09:00');

INSERT INTO teams (id, organization_id, name, active, created_at, updated_at)
SELECT md5('uat20260720:team:main:' || g)::uuid, c.main_org_id,
       (ARRAY['星火队','远航队','晨曦队','启航队','卓越队','同心队'])[g],
       true, now(), now()
FROM uat_context c CROSS JOIN generate_series(1, 6) AS g;

INSERT INTO teams (id, organization_id, name, active, created_at, updated_at)
SELECT md5('uat20260720:team:tenant:' || g)::uuid,
       md5('uat20260720:org:tenant-x')::uuid,
       '隔离组织-' || g || '队', true, now(), now()
FROM generate_series(1, 2) AS g;

-- Main organization: bootstrap admin + 104 accounts = 105.
INSERT INTO user_accounts
    (id, username, password_hash, display_name, enabled, password_change_required, created_at, updated_at)
SELECT md5('uat20260720:user:main:' || g)::uuid,
       CASE
           WHEN g = 1 THEN 'admin-b'
           WHEN g BETWEEN 2 AND 7 THEN 'leader-' || chr(95 + g)
           WHEN g = 8 THEN 'member-a1'
           WHEN g = 9 THEN 'member-a2'
           WHEN g = 23 THEN 'member-b1'
           WHEN g BETWEEN 8 AND 97 THEN 'member-' || lpad((g - 7)::text, 3, '0')
           WHEN g = 98 THEN 'viewer-a'
           ELSE 'viewer-' || lpad((g - 97)::text, 2, '0')
       END,
       c.shared_password_hash,
       CASE
           WHEN g = 1 THEN 'UAT第二管理员'
           WHEN g BETWEEN 2 AND 7 THEN 'UAT团队长' || (g - 1)
           WHEN g BETWEEN 8 AND 97 THEN 'UAT成员' || lpad((g - 7)::text, 3, '0')
           ELSE 'UAT只读者' || lpad((g - 97)::text, 2, '0')
       END,
       true, false, now(), now()
FROM uat_context c CROSS JOIN generate_series(1, 104) AS g;

INSERT INTO memberships (id, organization_id, user_id, status, created_at, updated_at)
SELECT md5('uat20260720:membership:main:' || g)::uuid,
       c.main_org_id,
       md5('uat20260720:user:main:' || g)::uuid,
       'ACTIVE', now(), now()
FROM uat_context c CROSS JOIN generate_series(1, 104) AS g;

INSERT INTO membership_roles (membership_id, role_id)
SELECT md5('uat20260720:membership:main:' || g)::uuid,
       CASE
           WHEN g = 1 THEN '00000000-0000-0000-0000-000000000001'::uuid
           WHEN g BETWEEN 2 AND 7 THEN '00000000-0000-0000-0000-000000000002'::uuid
           WHEN g BETWEEN 8 AND 97 THEN '00000000-0000-0000-0000-000000000003'::uuid
           ELSE '00000000-0000-0000-0000-000000000004'::uuid
       END
FROM generate_series(1, 104) AS g;

INSERT INTO team_members (team_id, membership_id, is_manager, created_at)
SELECT md5('uat20260720:team:main:' || (g - 1))::uuid,
       md5('uat20260720:membership:main:' || g)::uuid,
       true, now()
FROM generate_series(2, 7) AS g;

INSERT INTO team_members (team_id, membership_id, is_manager, created_at)
SELECT md5('uat20260720:team:main:' || (((g - 8) / 15) + 1))::uuid,
       md5('uat20260720:membership:main:' || g)::uuid,
       false, now()
FROM generate_series(8, 97) AS g;

-- Second organization: one admin, two managers, four members, one viewer.
INSERT INTO user_accounts
    (id, username, password_hash, display_name, enabled, password_change_required, created_at, updated_at)
SELECT md5('uat20260720:user:tenant:' || g)::uuid,
       CASE WHEN g = 1 THEN 'tenant-x-admin'
            WHEN g IN (2, 3) THEN 'tenant-x-leader-' || (g - 1)
            WHEN g BETWEEN 4 AND 7 THEN 'tenant-x-member-' || (g - 3)
            ELSE 'tenant-x-viewer' END,
       c.shared_password_hash,
       CASE WHEN g = 1 THEN '隔离组织管理员'
            WHEN g IN (2, 3) THEN '隔离组织团队长' || (g - 1)
            WHEN g BETWEEN 4 AND 7 THEN '隔离组织成员' || (g - 3)
            ELSE '隔离组织只读者' END,
       true, false, now(), now()
FROM uat_context c CROSS JOIN generate_series(1, 8) AS g;

INSERT INTO memberships (id, organization_id, user_id, status, created_at, updated_at)
SELECT md5('uat20260720:membership:tenant:' || g)::uuid,
       md5('uat20260720:org:tenant-x')::uuid,
       md5('uat20260720:user:tenant:' || g)::uuid,
       'ACTIVE', now(), now()
FROM generate_series(1, 8) AS g;

INSERT INTO membership_roles (membership_id, role_id)
SELECT md5('uat20260720:membership:tenant:' || g)::uuid,
       CASE WHEN g = 1 THEN '00000000-0000-0000-0000-000000000001'::uuid
            WHEN g IN (2, 3) THEN '00000000-0000-0000-0000-000000000002'::uuid
            WHEN g BETWEEN 4 AND 7 THEN '00000000-0000-0000-0000-000000000003'::uuid
            ELSE '00000000-0000-0000-0000-000000000004'::uuid END
FROM generate_series(1, 8) AS g;

INSERT INTO team_members (team_id, membership_id, is_manager, created_at)
SELECT md5('uat20260720:team:tenant:' || (g - 1))::uuid,
       md5('uat20260720:membership:tenant:' || g)::uuid,
       true, now()
FROM generate_series(2, 3) AS g;

INSERT INTO team_members (team_id, membership_id, is_manager, created_at)
SELECT md5('uat20260720:team:tenant:' || CASE WHEN g IN (4,5) THEN 1 ELSE 2 END)::uuid,
       md5('uat20260720:membership:tenant:' || g)::uuid,
       false, now()
FROM generate_series(4, 7) AS g;

-- Catalog totals: 60 dictionary items and 40 tags across both organizations.
INSERT INTO dictionary_items
    (id, organization_id, type, code, label, active, sort_order, created_at, updated_at)
SELECT md5('uat20260720:dictionary:main:' || g)::uuid, c.main_org_id,
       (ARRAY['SOURCE_CHANNEL','FOCUS_DIRECTION','INTERACTION_CHANNEL'])[((g - 1) % 3) + 1],
       'uat-main-' || g, 'UAT主组织字典' || g, true, 100 + g, now(), now()
FROM uat_context c CROSS JOIN generate_series(1, 21) AS g;

INSERT INTO dictionary_items
    (id, organization_id, type, code, label, active, sort_order, created_at, updated_at)
SELECT md5('uat20260720:dictionary:tenant:' || g)::uuid,
       md5('uat20260720:org:tenant-x')::uuid,
       (ARRAY['SOURCE_CHANNEL','FOCUS_DIRECTION','INTERACTION_CHANNEL'])[((g - 1) % 3) + 1],
       'uat-tenant-' || g, 'UAT隔离组织字典' || g, true, g, now(), now()
FROM generate_series(1, 20) AS g;

INSERT INTO tags
    (id, organization_id, name, category, color, is_system, active, created_by_membership_id, created_at, updated_at)
SELECT md5('uat20260720:tag:main:' || g)::uuid, c.main_org_id,
       'UAT主标签' || lpad(g::text, 2, '0'),
       (ARRAY['来源','意向','重点','活动批次'])[((g - 1) % 4) + 1], '#2563eb', false, true,
       c.main_admin_membership_id, now(), now()
FROM uat_context c CROSS JOIN generate_series(1, 20) AS g;

INSERT INTO tags
    (id, organization_id, name, category, color, is_system, active, created_by_membership_id, created_at, updated_at)
SELECT md5('uat20260720:tag:tenant:' || g)::uuid,
       md5('uat20260720:org:tenant-x')::uuid,
       'UAT隔离标签' || lpad(g::text, 2, '0'), '隔离', '#0f766e', false, true,
       md5('uat20260720:membership:tenant:1')::uuid, now(), now()
FROM generate_series(1, 10) AS g;

INSERT INTO registration_codes
    (id, organization_id, code_hash, code_hint, max_uses, used_count, expires_at, active,
     created_by_membership_id, created_at, disabled_at, version)
SELECT md5('uat20260720:registration:' || g)::uuid, c.main_org_id,
       md5('uat20260720:registration-hash:' || g) || md5('uat20260720:registration-hash-b:' || g),
       'UAT***' || lpad(g::text, 2, '0'),
       CASE WHEN g % 3 = 0 THEN 10 ELSE 1 END,
       CASE WHEN g % 10 = 0 THEN 1 ELSE 0 END,
       CASE WHEN g % 6 = 0 THEN now() - interval '1 day' ELSE now() + interval '30 days' END,
       g % 5 <> 0,
       c.main_admin_membership_id, now() - (g || ' hours')::interval,
       CASE WHEN g % 5 = 0 THEN now() ELSE NULL END, 0
FROM uat_context c CROSS JOIN generate_series(1, 30) AS g;

-- 51,000 customers: 50,000 primary tenant and 1,000 isolated tenant.
INSERT INTO customers
    (id, organization_id, owner_membership_id, team_id, visibility, full_name, nickname,
     phone, normalized_phone, wechat_id, normalized_wechat_id, qq_id, normalized_qq_id,
     school, major, grade, city, identity_type, demand_summary, intention_level, urgency_level,
     verification_status, current_status, owner_notes, last_contact_at, next_follow_up_at,
     archived_at, created_by_membership_id, created_at, updated_at, version,
     lead_review_status, lead_review_reason, lead_reviewed_by_membership_id, lead_reviewed_at)
SELECT md5('uat20260720:customer:main:' || g)::uuid, c.main_org_id,
       md5('uat20260720:membership:main:' || (((g - 1) % 90) + 8))::uuid,
       md5('uat20260720:team:main:' || ((((g - 1) % 90) / 15) + 1))::uuid,
       CASE WHEN g % 100 < 25 THEN 'PRIVATE'
            WHEN g % 100 < 60 THEN 'TEAM' ELSE 'ORGANIZATION' END,
       CASE WHEN g <= 30 THEN 'UAT-20260720-黄金客户-' || lpad(g::text, 2, '0')
            WHEN g % 33 = 0 THEN 'UAT-20260720-相似姓名-' || lpad((g % 20)::text, 2, '0')
            ELSE 'UAT-20260720-规模客户-' || lpad(g::text, 5, '0') END,
       CASE WHEN g % 4 = 0 THEN '昵称' || g ELSE NULL END,
       '13' || lpad((CASE WHEN g % 50 = 0 THEN g - 1 ELSE g END)::text, 9, '0'),
       '13' || lpad((CASE WHEN g % 50 = 0 THEN g - 1 ELSE g END)::text, 9, '0'),
       CASE WHEN g % 3 = 0 THEN 'wx_uat_' || g ELSE NULL END,
       CASE WHEN g % 3 = 0 THEN 'wx_uat_' || g ELSE NULL END,
       CASE WHEN g % 5 = 0 THEN (800000000 + g)::text ELSE NULL END,
       CASE WHEN g % 5 = 0 THEN (800000000 + g)::text ELSE NULL END,
       'UAT大学' || ((g - 1) % 40 + 1), '专业' || ((g - 1) % 30 + 1),
       CASE WHEN g % 5 = 0 THEN '26级' ELSE (22 + (g % 5))::text || '级' END,
       '城市' || ((g - 1) % 30 + 1),
       (ARRAY['新生','转专业','在校生','毕业生','家长代问','其他'])[((g - 1) % 6) + 1],
       '固定种子 20260720 的正式规模合成客户',
       CASE WHEN g % 20 < 3 THEN '高' WHEN g % 2 = 0 THEN '中' ELSE '低' END,
       (ARRAY['低','中','高'])[((g - 1) % 3) + 1],
       (ARRAY['未核实','已核实','部分待确认'])[((g - 1) % 3) + 1],
       CASE WHEN g % 10 = 0 THEN '归档'
            ELSE (ARRAY['初次接触','待核实','已沟通','跟进中','高意向','待决定','已转化','暂缓','无效'])[((g - 1) % 9) + 1] END,
       CASE WHEN g <= 30 THEN '黄金业务样本' ELSE NULL END,
       timestamptz '2026-07-15 09:00:00+08' + ((g % 37) || ' days')::interval,
       CASE WHEN g % 10 = 0 THEN NULL ELSE now() + (((g % 9) - 4) || ' days')::interval END,
       CASE WHEN g % 10 = 0 THEN now() - interval '1 day' ELSE NULL END,
       md5('uat20260720:membership:main:' || (((g + 12) % 90) + 8))::uuid,
       timestamptz '2026-07-15 08:00:00+08' + ((g % 37) || ' days')::interval,
       now() - ((g % 600) || ' minutes')::interval, 0,
       CASE WHEN g % 5 <> 0 THEN 'PENDING'
            WHEN (g / 5) % 10 < 7 THEN 'VALID'
            WHEN (g / 5) % 10 < 9 THEN 'INVALID' ELSE 'PENDING' END,
       CASE WHEN g % 5 = 0 AND (g / 5) % 10 IN (7,8) THEN '合成样本无效线索' ELSE NULL END,
       CASE WHEN g % 5 = 0 AND (g / 5) % 10 < 9 THEN c.main_admin_membership_id ELSE NULL END,
       CASE WHEN g % 5 = 0 AND (g / 5) % 10 < 9 THEN now() ELSE NULL END
FROM uat_context c CROSS JOIN generate_series(1, 50000) AS g;

INSERT INTO customers
    (id, organization_id, owner_membership_id, team_id, visibility, full_name,
     phone, normalized_phone, school, major, grade, city, identity_type, demand_summary,
     intention_level, urgency_level, verification_status, current_status,
     created_by_membership_id, created_at, updated_at, version, lead_review_status)
SELECT md5('uat20260720:customer:tenant:' || g)::uuid,
       md5('uat20260720:org:tenant-x')::uuid,
       md5('uat20260720:membership:tenant:' || (((g - 1) % 4) + 4))::uuid,
       md5('uat20260720:team:tenant:' || CASE WHEN (g - 1) % 4 < 2 THEN 1 ELSE 2 END)::uuid,
       CASE WHEN g % 4 = 0 THEN 'PRIVATE' WHEN g % 4 = 1 THEN 'TEAM' ELSE 'ORGANIZATION' END,
       'UAT-20260720-隔离组织客户-' || lpad(g::text, 4, '0'),
       '15' || lpad(g::text, 9, '0'), '15' || lpad(g::text, 9, '0'),
       '隔离大学', '隔离专业', CASE WHEN g % 5 = 0 THEN '26级' ELSE '25级' END,
       '隔离城市', '在校生', '第二组织隔离验证',
       (ARRAY['低','中','高'])[((g - 1) % 3) + 1],
       (ARRAY['低','中','高'])[((g - 1) % 3) + 1],
       '已核实', CASE WHEN g % 10 = 0 THEN '归档' ELSE '跟进中' END,
       md5('uat20260720:membership:tenant:' || (((g - 1) % 4) + 4))::uuid,
       now() - ((g % 37) || ' days')::interval, now(), 0,
       CASE WHEN g % 10 < 7 THEN 'VALID' WHEN g % 10 < 9 THEN 'INVALID' ELSE 'PENDING' END
FROM generate_series(1, 1000) AS g;

INSERT INTO customer_focus_directions (customer_id, direction)
SELECT md5('uat20260720:customer:main:' || g)::uuid,
       (ARRAY['就业','考研','考公','升学','转专业','技能提升'])[((g - 1) % 6) + 1]
FROM generate_series(1, 50000) AS g;

INSERT INTO customer_tags (customer_id, tag_id)
SELECT md5('uat20260720:customer:main:' || g)::uuid,
       md5('uat20260720:tag:main:' || (((g - 1) % 20) + 1))::uuid
FROM generate_series(1, 50000) AS g;

-- Exactly five communications per primary-tenant customer.
INSERT INTO interactions
    (id, organization_id, customer_id, contacted_at, channel, summary, details, outcome,
     next_follow_up_at, created_by_membership_id, created_at)
SELECT md5('uat20260720:interaction:' || g)::uuid, c.main_org_id,
       md5('uat20260720:customer:main:' || (((g - 1) % 50000) + 1))::uuid,
       timestamptz '2026-07-15 10:00:00+08' + ((g % 37) || ' days')::interval,
       (ARRAY['微信','电话','QQ','面谈','活动'])[((g - 1) % 5) + 1],
       'UAT-20260720-沟通-' || lpad(g::text, 6, '0'),
       '正式规模合成沟通详情', '已记录',
       now() + ((g % 10) || ' days')::interval,
       md5('uat20260720:membership:main:' || ((((g - 1) % 50000) % 90) + 8))::uuid,
       now() - ((g % 720) || ' minutes')::interval
FROM uat_context c CROSS JOIN generate_series(1, 250000) AS g;

-- Map reminders only to non-archived primary customers.
INSERT INTO reminders
    (id, organization_id, customer_id, assigned_membership_id, remind_at, title, status,
     delivery_status, completed_at, created_at, updated_at, version)
SELECT md5('uat20260720:reminder:' || g)::uuid, c.main_org_id,
       customer.id, customer.owner_membership_id,
       CASE WHEN g % 4 = 0 THEN now() - interval '2 days'
            WHEN g % 4 = 1 THEN date_trunc('day', now()) + interval '13 hours'
            ELSE now() + ((g % 14 + 1) || ' days')::interval END,
       'UAT-20260720-提醒-' || lpad(g::text, 5, '0'),
       CASE WHEN g % 5 IN (0,1) THEN 'PENDING'
            WHEN g % 5 IN (2,3) THEN 'COMPLETED' ELSE 'CANCELLED' END,
       CASE WHEN g % 3 = 0 THEN 'SENT' ELSE 'UNSENT' END,
       CASE WHEN g % 5 IN (2,3) THEN now() ELSE NULL END,
       now() - ((g % 500) || ' minutes')::interval, now(), 0
FROM uat_context c
CROSS JOIN generate_series(1, 40000) AS g
JOIN customers customer
  ON customer.id = md5('uat20260720:customer:main:' ||
      ((((g - 1) / 9) * 10) + ((g - 1) % 9) + 1))::uuid;

-- 20 content directions, 15 active and 5 disabled.
INSERT INTO content_directions
    (id, organization_id, title, description, active, created_by_membership_id, created_at, updated_at)
SELECT md5('uat20260720:content-direction:' || g)::uuid, c.main_org_id,
       'UAT-20260720-内容方向-' || lpad(g::text, 2, '0'),
       '固定种子内容方向', g <= 15, c.main_admin_membership_id, now(), now()
FROM uat_context c CROSS JOIN generate_series(1, 20) AS g;

-- 90 members x 2 platforms x 37 days = 6,660 posts.
INSERT INTO content_posts
    (id, organization_id, membership_id, platform, publish_date, direction_id, topic,
     content_url, view_count, like_count, favorite_count, original_declared, review_status,
     review_reason, reviewed_by_membership_id, reviewed_at, created_at, updated_at, version)
SELECT md5('uat20260720:content:' || member_no || ':' || platform.code || ':' || day_no)::uuid,
       c.main_org_id, md5('uat20260720:membership:main:' || (member_no + 7))::uuid,
       platform.code, date '2026-07-15' + day_no,
       md5('uat20260720:content-direction:' || (((member_no + day_no + platform.platform_offset) % 15) + 1))::uuid,
       'UAT-20260720-' || platform.code || '-主题-' || member_no || '-' || day_no,
       'https://example.invalid/uat/' || platform.code || '/' || member_no || '/' || day_no,
       100 + member_no * 10 + day_no, 10 + (member_no + day_no) % 100,
       3 + (member_no + day_no) % 40, true,
       CASE WHEN (member_no + day_no + platform.platform_offset) % 10 < 7 THEN 'VALID'
            WHEN (member_no + day_no + platform.platform_offset) % 10 < 9 THEN 'INVALID' ELSE 'SUBMITTED' END,
       CASE WHEN (member_no + day_no + platform.platform_offset) % 10 IN (7,8) THEN '质量未达标' ELSE NULL END,
       CASE WHEN (member_no + day_no + platform.platform_offset) % 10 < 9 THEN c.main_admin_membership_id ELSE NULL END,
       CASE WHEN (member_no + day_no + platform.platform_offset) % 10 < 9 THEN now() ELSE NULL END,
       timestamptz '2026-07-15 12:00:00+08' + (day_no || ' days')::interval,
       now(), 0
FROM uat_context c
CROSS JOIN generate_series(1, 90) AS member_no
CROSS JOIN generate_series(0, 36) AS day_no
CROSS JOIN (VALUES ('XIAOHONGSHU', 0), ('DOUYIN', 1)) AS platform(code, platform_offset);

-- 90 members x 37 days = 3,330 reports.
INSERT INTO daily_reports
    (id, organization_id, membership_id, report_date, deep_communication_count,
     today_issues, tomorrow_plan, submitted_at, updated_at, version)
SELECT md5('uat20260720:daily-report:' || member_no || ':' || day_no)::uuid,
       c.main_org_id, md5('uat20260720:membership:main:' || (member_no + 7))::uuid,
       date '2026-07-15' + day_no, (member_no + day_no) % 12,
       'UAT-20260720-今日问题-' || member_no || '-' || day_no,
       'UAT-20260720-明日计划-' || member_no || '-' || day_no,
       timestamptz '2026-07-15 20:00:00+08' + (day_no || ' days')::interval,
       now(), 0
FROM uat_context c
CROSS JOIN generate_series(1, 90) AS member_no
CROSS JOIN generate_series(0, 36) AS day_no;

-- Exactly 60 work tasks: 20 mandatory, 20 assigned, 20 self-claim.
INSERT INTO work_tasks
    (id, organization_id, title, description, distribution_type, start_at, due_at,
     max_claimants, status, created_by_membership_id, created_at, updated_at, version)
SELECT md5('uat20260720:task:' || g)::uuid, c.main_org_id,
       'UAT-20260720-工作任务-' || lpad(g::text, 2, '0'), '固定种子任务说明',
       CASE WHEN g <= 20 THEN 'MANDATORY' WHEN g <= 40 THEN 'ASSIGNED' ELSE 'SELF_CLAIM' END,
       now() - interval '1 day', now() + interval '45 days',
       CASE WHEN g > 40 THEN CASE WHEN g = 41 THEN 1 ELSE 20 END ELSE NULL END,
       CASE WHEN g % 15 = 0 THEN 'CLOSED' ELSE 'PUBLISHED' END,
       c.main_admin_membership_id, now(), now(), 0
FROM uat_context c CROSS JOIN generate_series(1, 60) AS g;

INSERT INTO work_task_assignments
    (id, organization_id, task_id, membership_id, assignment_source, status, updated_at, version)
SELECT md5('uat20260720:assignment:mandatory:' || task_no || ':' || eligible_no)::uuid,
       c.main_org_id, md5('uat20260720:task:' || task_no)::uuid,
       CASE WHEN eligible_no <= 6
            THEN md5('uat20260720:membership:main:' || (eligible_no + 1))::uuid
            ELSE md5('uat20260720:membership:main:' || (eligible_no + 1))::uuid END,
       'MANDATORY', 'PENDING', now(), 0
FROM uat_context c
CROSS JOIN generate_series(1, 20) AS task_no
CROSS JOIN generate_series(1, 96) AS eligible_no;

INSERT INTO work_task_assignments
    (id, organization_id, task_id, membership_id, assignment_source, status, updated_at, version)
SELECT md5('uat20260720:assignment:assigned:' || task_no || ':' || member_no)::uuid,
       c.main_org_id, md5('uat20260720:task:' || task_no)::uuid,
       md5('uat20260720:membership:main:' || (member_no + 7))::uuid,
       'ASSIGNED', 'PENDING', now(), 0
FROM uat_context c
CROSS JOIN generate_series(21, 40) AS task_no
CROSS JOIN generate_series(1, 10) AS member_no;

INSERT INTO announcements
    (id, organization_id, author_membership_id, title, content, priority, pinned,
     status, published_at, archived_at, created_at, updated_at, version)
SELECT md5('uat20260720:announcement:' || g)::uuid, c.main_org_id,
       CASE WHEN g % 7 = 0 THEN md5('uat20260720:membership:main:' || ((g % 6) + 2))::uuid
            ELSE c.main_admin_membership_id END,
       'UAT-20260720-公告-' || lpad(g::text, 3, '0'), '固定种子公告正文 ' || g,
       (ARRAY['NORMAL','IMPORTANT','URGENT'])[((g - 1) % 3) + 1],
       g % 10 = 0, CASE WHEN g % 8 = 0 THEN 'ARCHIVED' ELSE 'PUBLISHED' END,
       now() - (g || ' hours')::interval,
       CASE WHEN g % 8 = 0 THEN now() ELSE NULL END,
       now() - (g || ' hours')::interval, now(), 0
FROM uat_context c CROSS JOIN generate_series(1, 100) AS g;

INSERT INTO uat_seed_manifest (seed, manifest)
VALUES (20260720, jsonb_build_object(
    'organizations', 2, 'teams', 8, 'accounts', 113, 'customers', 51000,
    'interactions', 250000, 'reminders', 40000, 'tasks', 60,
    'contentPosts', 6660, 'dailyReports', 3330, 'announcements', 100));

COMMIT;
ANALYZE;

\echo 'seed 20260720 applied successfully'
