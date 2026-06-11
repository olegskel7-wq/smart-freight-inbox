-- Phase 1.4: extended email classification.
-- Business type assigned by the AI classifier (RFQ / STATUS_UPDATE / BOOKING_CONFIRM /
-- INVOICE_QUERY / DOCUMENT_REQUEST / GENERAL). Nullable: pre-1.4 tickets, retry-path
-- re-entries and IRRELEVANT tickets carry no type.
ALTER TABLE email_ticket ADD COLUMN ticket_type VARCHAR(32);

-- Feature flag (same convention as V12): a missing row defaults to enabled in code,
-- seeding makes the flag discoverable and toggleable on /settings.
INSERT INTO app_setting (key, value) VALUES
    ('feature.ticket-type', 'true')
ON CONFLICT (key) DO NOTHING;
