ALTER TABLE leadership_screen_settings
    ADD COLUMN featured_grade varchar(4) NOT NULL DEFAULT '2026';

ALTER TABLE leadership_screen_settings
    ADD CONSTRAINT leadership_featured_grade_check
        CHECK (featured_grade ~ '^(19|20)[0-9]{2}$');

UPDATE leadership_screen_settings
SET scene_order = scene_order
        || CASE WHEN scene_order ? 'followUpRisk' THEN '[]'::jsonb ELSE '["followUpRisk"]'::jsonb END
        || CASE WHEN scene_order ? 'schoolComparison' THEN '[]'::jsonb ELSE '["schoolComparison"]'::jsonb END,
    enabled_scenes = enabled_scenes
        || CASE WHEN enabled_scenes ? 'followUpRisk' THEN '[]'::jsonb ELSE '["followUpRisk"]'::jsonb END
        || CASE WHEN enabled_scenes ? 'schoolComparison' THEN '[]'::jsonb ELSE '["schoolComparison"]'::jsonb END;

ALTER TABLE leadership_screen_settings
    ALTER COLUMN scene_order SET DEFAULT
        '["time","culture","briefing","trend","champions","funnel","mission","followUpRisk","schoolComparison"]'::jsonb,
    ALTER COLUMN enabled_scenes SET DEFAULT
        '["time","culture","briefing","trend","champions","funnel","mission","followUpRisk","schoolComparison"]'::jsonb;
