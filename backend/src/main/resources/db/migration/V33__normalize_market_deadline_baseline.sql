UPDATE market_deadline_rule_versions
SET effective_from = TIMESTAMPTZ '1970-01-01 00:00:00+00'
WHERE effective_from = '-infinity'::timestamptz;

CREATE OR REPLACE FUNCTION seed_market_deadline_rule_for_organization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO market_deadline_rule_versions (
        id, organization_id, version, effective_from, mode,
        a_peak_hours, b_peak_hours, a_off_peak_hours, b_off_peak_hours, weekends_count
    ) VALUES (
        gen_random_uuid(), NEW.id, 1, TIMESTAMPTZ '1970-01-01 00:00:00+00', 'SHADOW',
        72, 96, 168, 216, true
    )
    ON CONFLICT (organization_id, version) DO NOTHING;
    RETURN NEW;
END;
$$;
