-- Phase 1.6: auto-send threshold.
-- ai_confidence: deterministic heuristic score (0.00-1.00) computed by the pipeline from
-- verifiable facts (type classified, identifier extracted, item found in the backend,
-- rate matched). Stored on every DRAFT_READY ticket regardless of the feature flag so the
-- score distribution accumulates for threshold tuning. Null for pre-1.6 tickets and for
-- tickets that never reached drafting.
-- DOUBLE PRECISION (not NUMERIC): the entity field is a Java Double and Hibernate schema
-- validation insists on a float column; two-decimal rounding is done in code.
ALTER TABLE email_ticket ADD COLUMN ai_confidence DOUBLE PRECISION;

-- feature.auto-send deliberately breaks the "missing row = enabled" convention of the other
-- feature.* flags: this one sends emails to real customers without an operator, so both the
-- seeded row AND the code-level default (SettingsService.isFeatureEnabled(..., false)) are OFF.
-- autosend.threshold: minimum ai_confidence for an automatic send.
-- autosend.types: comma-separated TicketType allowlist; starts with the safest type only —
-- factual "where is my shipment" answers backed by TMS data. RFQ (prices!) stays manual.
INSERT INTO app_setting (key, value) VALUES
    ('feature.auto-send', 'false'),
    ('autosend.threshold', '0.8'),
    ('autosend.types', 'STATUS_UPDATE')
ON CONFLICT (key) DO NOTHING;
