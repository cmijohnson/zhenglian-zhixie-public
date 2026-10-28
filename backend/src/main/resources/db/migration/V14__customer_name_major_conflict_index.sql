CREATE INDEX IF NOT EXISTS idx_customers_name_major_conflict
    ON customers (
        organization_id,
        lower(regexp_replace(btrim(full_name), '[[:space:]]+', '', 'g')),
        lower(regexp_replace(btrim(major), '[[:space:]]+', '', 'g'))
    )
    WHERE major IS NOT NULL AND btrim(major) <> '';
