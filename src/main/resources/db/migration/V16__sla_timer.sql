-- Phase 1.5: SLA timer on tickets.
-- sla_deadline_at: reply-by deadline = created_at + per-type window (config sla.minutes.*).
-- Set at ingest with the GENERAL window (type unknown yet), refined after classification,
-- cleared for IRRELEVANT. Null for pre-1.5 tickets and when feature.sla-timer is off.
-- sla_breached: flipped by SlaBreachScheduler when the deadline passes while the ticket
-- is still open; stays true after closing (historical record for analytics).
ALTER TABLE email_ticket ADD COLUMN sla_deadline_at TIMESTAMP;
ALTER TABLE email_ticket ADD COLUMN sla_breached BOOLEAN NOT NULL DEFAULT FALSE;

-- The breach scheduler polls "open tickets past deadline, not yet flagged" every minute —
-- partial index keeps that scan cheap as the table grows.
CREATE INDEX idx_email_ticket_sla_open
    ON email_ticket (sla_deadline_at)
    WHERE sla_breached = FALSE AND deleted = FALSE;

-- Feature flag (same convention as V12/V15): missing row defaults to enabled in code,
-- seeding makes the flag discoverable and toggleable on /settings.
INSERT INTO app_setting (key, value) VALUES
    ('feature.sla-timer', 'true')
ON CONFLICT (key) DO NOTHING;
