ALTER TABLE dictionary_items
    DROP CONSTRAINT IF EXISTS dictionary_items_type_check;

ALTER TABLE dictionary_items
    ADD CONSTRAINT dictionary_items_type_check
        CHECK (type IN ('SOURCE_CHANNEL', 'FOCUS_DIRECTION', 'INTERACTION_CHANNEL', 'SCHOOL'));

CREATE UNIQUE INDEX uq_dictionary_school_label
    ON dictionary_items (organization_id, lower(label))
    WHERE type = 'SCHOOL' AND active = true;

INSERT INTO dictionary_items (id, organization_id, type, code, label, sort_order)
SELECT md5(organization.id::text || ':school:jiangsu-university')::uuid,
       organization.id,
       'SCHOOL',
       'JIANGSU_UNIVERSITY',
       '示例大学',
       1
FROM organizations organization
ON CONFLICT (organization_id, type, code) DO NOTHING;

INSERT INTO dictionary_items (id, organization_id, type, code, label, sort_order)
SELECT md5(organization.id::text || ':school:jiangsu-university-of-science-and-technology')::uuid,
       organization.id,
       'SCHOOL',
       'JIANGSU_UNIVERSITY_OF_SCIENCE_AND_TECHNOLOGY',
       '示例科技大学',
       2
FROM organizations organization
ON CONFLICT (organization_id, type, code) DO NOTHING;
