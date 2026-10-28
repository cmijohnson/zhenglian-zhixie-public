ALTER TABLE reminders
    ADD COLUMN source_analysis_version_id uuid;

ALTER TABLE reminders
    ADD CONSTRAINT reminders_source_analysis_version_fk
        FOREIGN KEY (source_analysis_version_id)
            REFERENCES customer_analysis_versions(id)
            ON DELETE SET NULL;

CREATE INDEX reminders_source_analysis_version_idx
    ON reminders (source_analysis_version_id)
    WHERE source_analysis_version_id IS NOT NULL;
