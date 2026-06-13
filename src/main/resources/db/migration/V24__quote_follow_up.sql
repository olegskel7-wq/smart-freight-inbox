-- Phase 2.5: Quote Follow-up Automation (proactive nudge on a silent quote).
--
-- The reactive pipeline answers an RFQ and stops. In freight sales the money is in the
-- follow-up: a quote sent and never chased is a lost deal. This phase adds a PROACTIVE nudge
-- — when a quote was emailed to a customer and they go silent, the platform drafts (and,
-- through the auto-send gate, optionally sends) one short "just following up" message after a
-- delay, then stops. The quoted enquiry itself is the subscription, mirroring shipment_watch
-- (V22): a sent RFQ reply gives us (tenant, customer_email, ticket_id, quote_sent_at).

-- One follow-up per quoted RFQ ticket. quote_sent_at anchors the delay; active flips to false
-- once the nudge fires OR the customer replies (sender-based detection — see QuoteFollowUpScheduler).
CREATE TABLE quote_follow_up (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES tenant(id),
    ticket_id      BIGINT       NOT NULL,
    customer_email VARCHAR(255) NOT NULL,
    quote_sent_at  TIMESTAMP    NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),
    -- One subscription per quoted ticket within a tenant: re-sending the same quote refreshes
    -- the existing follow-up instead of duplicating it.
    UNIQUE (tenant_id, ticket_id)
);

-- Same two-layer isolation as the V19/V20/V21/V22 tables: RLS guards native SQL / raw JDBC,
-- Hibernate @TenantId guards entity queries. Grants arrive automatically via the
-- ALTER DEFAULT PRIVILEGES from V19 (this migration runs as the admin role).
ALTER TABLE quote_follow_up ENABLE ROW LEVEL SECURITY;
ALTER TABLE quote_follow_up FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quote_follow_up
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

-- feature.quote-followup follows feature.auto-send / feature.status-update-automation: it
-- emails real customers without an operator, so both the seeded row AND the code default are OFF.
-- quotefollowup.delay-hours: hours of silence after the quote was sent before a single nudge fires.
-- ai.quote-followup-prompt: drafting template for the nudge; placeholder {originalEnquiry} is
-- substituted at runtime with the customer's original RFQ text.
INSERT INTO app_setting (key, value) VALUES
    ('feature.quote-followup', 'false'),
    ('quotefollowup.delay-hours', '48'),
    ('ai.quote-followup-prompt',
'You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
You sent this customer a freight quote a couple of days ago and have not heard back.
Write a short, polite follow-up email gently checking whether they have any questions about the quote and whether they would like to proceed.
This is NOT a reply to a new customer message — you are reaching out proactively. Open accordingly.
Do NOT restate prices or invent any new figures. Keep it warm, brief and low-pressure.

The customer''s original enquiry was:
{originalEnquiry}

Write only the email body (no subject line). Keep it to a few sentences.')
ON CONFLICT (key) DO NOTHING;
