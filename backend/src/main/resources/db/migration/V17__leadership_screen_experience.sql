ALTER TABLE announcements
    ADD COLUMN show_on_leadership_screen boolean NOT NULL DEFAULT false;

CREATE INDEX announcements_leadership_screen_idx
    ON announcements (organization_id, status, show_on_leadership_screen, published_at DESC)
    WHERE show_on_leadership_screen = true;
