-- Preserve historical source rows and customer references, while replacing only
-- the original bootstrap choices shown in new/edit customer forms.
UPDATE dictionary_items
SET active = false,
    updated_at = now()
WHERE type = 'SOURCE_CHANNEL'
  AND active = true
  AND code IN ('item-1', 'item-2', 'item-3', 'item-4', 'item-5')
  AND label IN ('活动', '朋友推荐', '社群', '线上咨询', '其他');

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY organization_id, lower(label)
               ORDER BY sort_order, created_at, id
           ) AS position
    FROM dictionary_items
    WHERE type = 'SOURCE_CHANNEL' AND active = true
)
UPDATE dictionary_items item
SET active = false,
    updated_at = now()
FROM ranked
WHERE item.id = ranked.id AND ranked.position > 1;

WITH defaults(code, label, sort_order) AS (
    VALUES ('DEFAULT_XIAOHONGSHU', '小红书', 1),
           ('DEFAULT_DOUYIN', '抖音', 2),
           ('DEFAULT_FRESHMAN_GROUP', '新生群', 3),
           ('DEFAULT_OPERATOR', '运营商', 4),
           ('DEFAULT_FRIEND_REFERRAL', '朋友推荐', 5),
           ('DEFAULT_OTHER', '其他', 6)
)
INSERT INTO dictionary_items (id, organization_id, type, code, label, sort_order)
SELECT md5(organization.id::text || ':source:' || defaults.code)::uuid,
       organization.id,
       'SOURCE_CHANNEL',
       defaults.code,
       defaults.label,
       defaults.sort_order
FROM organizations organization
CROSS JOIN defaults
WHERE NOT EXISTS (
    SELECT 1
    FROM dictionary_items existing
    WHERE existing.organization_id = organization.id
      AND existing.type = 'SOURCE_CHANNEL'
      AND existing.active = true
      AND lower(existing.label) = lower(defaults.label)
)
ON CONFLICT (organization_id, type, code) DO UPDATE
SET label = excluded.label,
    active = true,
    sort_order = excluded.sort_order,
    updated_at = now();

CREATE UNIQUE INDEX uq_dictionary_source_label
    ON dictionary_items (organization_id, lower(label))
    WHERE type = 'SOURCE_CHANNEL' AND active = true;
