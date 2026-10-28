WITH quality_tag_state AS (
    SELECT customer_tag.customer_id,
           count(*) quality_count,
           count(*) FILTER (WHERE tag.name IN ('A类用户','B类用户','C类用户')) canonical_count,
           min(tag.name) FILTER (WHERE tag.name IN ('A类用户','B类用户','C类用户')) quality_name
    FROM customer_tags customer_tag
    JOIN tags tag ON tag.id=customer_tag.tag_id
    WHERE tag.category='客户质量'
    GROUP BY customer_tag.customer_id
), single_quality_tag AS (
    SELECT customer_id, quality_name
    FROM quality_tag_state
    WHERE quality_count=1 AND canonical_count=1
)
INSERT INTO customer_classifications (
    id, organization_id, customer_id, revision, level, recognition_reason, potential_demand,
    a_signals, evidence_interaction_ids, submitted_by_membership_id, team_id_snapshot, source, created_at
)
SELECT gen_random_uuid(), customer.organization_id, customer.id, 1,
       substring(quality.quality_name from 1 for 1),
       '历史标签迁移，待补充结构化认定依据', '未确认',
       CASE WHEN quality.quality_name='A类用户'
            THEN '["历史A类标签，待复核"]'::jsonb ELSE '[]'::jsonb END,
       '[]'::jsonb, customer.created_by_membership_id, customer.team_id, 'LEGACY_IMPORT', now()
FROM customers customer
JOIN single_quality_tag quality ON quality.customer_id=customer.id
WHERE NOT EXISTS (
    SELECT 1 FROM customer_classifications classification
    WHERE classification.organization_id=customer.organization_id
      AND classification.customer_id=customer.id
)
ON CONFLICT (customer_id, revision) DO NOTHING;

UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000026',
    '2026.09.03.1',
    '历史客户沟通兼容修复',
    '历史 A/B/C 客户现在可以正常新增沟通和维护普通标签，原有等级继续保留，等级变更仍需提交结构化事实依据。',
    '{"修复":["修复仅有旧版 A/B/C 标签的客户无法新增沟通记录","修复旧 Android 新增时间线时空标签集合可能清除普通标签","补全遗漏的历史等级兼容记录且不伪造沟通证据"],"改进":["沟通记录只在用户明确修改时同步普通标签","客户质量在沟通表单中只读展示，统一从结构化分级模块修改","管理员治理台可查看多质量标签和等级不一致的数据问题"],"安全":["A/B/C 新增、删除和切换继续要求结构化事实依据","历史分级、客户标签和沟通时间线均不被覆盖或删除"]}'::jsonb,
    '2026-09-03T00:00:00+08:00',
    true,
    true
);

INSERT INTO mobile_app_releases(
    id,
    platform,
    version_name,
    version_code,
    minimum_supported_version_code,
    mandatory,
    notes,
    apk_path,
    apk_size,
    apk_sha256,
    manifest_signature,
    published,
    released_at
) VALUES (
    '93f3398f-2337-4085-be88-1d730deeb2c6',
    'ANDROID',
    '0.2.2',
    7,
    1,
    false,
    '修复历史 A/B/C 客户新增沟通被错误拒绝，并避免新增时间线时空标签集合误清普通标签。',
    'android/yingge-cloud-0.2.2.apk',
    5205993,
    '381e3febb8f65bbb4df0feed01df0953a26ec9049ed9c52728b1634ed332ff87',
    null,
    true,
    '2026-09-03T02:30:00+08:00'
)
ON CONFLICT (platform, version_code) DO UPDATE SET
    version_name = excluded.version_name,
    minimum_supported_version_code = excluded.minimum_supported_version_code,
    mandatory = excluded.mandatory,
    notes = excluded.notes,
    apk_path = excluded.apk_path,
    apk_size = excluded.apk_size,
    apk_sha256 = excluded.apk_sha256,
    manifest_signature = excluded.manifest_signature,
    published = excluded.published,
    released_at = excluded.released_at;
