ALTER TABLE leadership_screen_settings
    ALTER COLUMN enabled_events SET DEFAULT
        '["zeroBreakthrough","customerAdded","customerUpdated","milestone","trendSurge","conversionStage","taskPublished","taskMilestone","rankingChange","teamMomentum"]'::jsonb;

UPDATE leadership_screen_settings
SET enabled_events = enabled_events
        || CASE WHEN NOT enabled_events ? 'customerAdded'
            THEN '["customerAdded"]'::jsonb ELSE '[]'::jsonb END
        || CASE WHEN NOT enabled_events ? 'customerUpdated'
            THEN '["customerUpdated"]'::jsonb ELSE '[]'::jsonb END,
    updated_at = now(),
    version = version + 1
WHERE NOT enabled_events ? 'customerAdded'
   OR NOT enabled_events ? 'customerUpdated';
