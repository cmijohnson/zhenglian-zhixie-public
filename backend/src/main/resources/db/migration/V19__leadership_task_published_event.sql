ALTER TABLE leadership_screen_settings
    ALTER COLUMN enabled_events SET DEFAULT
        '["zeroBreakthrough","milestone","trendSurge","conversionStage","taskPublished","taskMilestone","rankingChange","teamMomentum"]'::jsonb;

UPDATE leadership_screen_settings
SET enabled_events = enabled_events || '["taskPublished"]'::jsonb,
    updated_at = now(),
    version = version + 1
WHERE NOT enabled_events ? 'taskPublished';
