CREATE OR REPLACE FUNCTION seed_customer_quality_tags_for_organization()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO tags (
        id, organization_id, name, category, color, is_system, active,
        created_by_membership_id, created_at, updated_at
    )
    SELECT
        md5(NEW.id::text || quality.code)::uuid,
        NEW.id,
        quality.name,
        '客户质量',
        quality.color,
        true,
        true,
        null,
        now(),
        now()
    FROM (VALUES
        ('A', 'A类用户', '#16A34A'),
        ('B', 'B类用户', '#F59E0B'),
        ('C', 'C类用户', '#64748B')
    ) AS quality(code, name, color)
    ON CONFLICT (organization_id, name) DO UPDATE
       SET category = EXCLUDED.category,
           color = EXCLUDED.color,
           is_system = true,
           active = true,
           updated_at = now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS organizations_seed_customer_quality_tags ON organizations;
CREATE TRIGGER organizations_seed_customer_quality_tags
AFTER INSERT ON organizations
FOR EACH ROW
EXECUTE FUNCTION seed_customer_quality_tags_for_organization();

INSERT INTO tags (
    id, organization_id, name, category, color, is_system, active,
    created_by_membership_id, created_at, updated_at
)
SELECT
    md5(organization.id::text || quality.code)::uuid,
    organization.id,
    quality.name,
    '客户质量',
    quality.color,
    true,
    true,
    null,
    now(),
    now()
FROM organizations organization
CROSS JOIN (VALUES
    ('A', 'A类用户', '#16A34A'),
    ('B', 'B类用户', '#F59E0B'),
    ('C', 'C类用户', '#64748B')
) AS quality(code, name, color)
ON CONFLICT (organization_id, name) DO UPDATE
   SET category = EXCLUDED.category,
       color = EXCLUDED.color,
       is_system = true,
       active = true,
       updated_at = now();
