-- Phase 2.4: Status Update Automation (proactive milestone notifications).
--
-- The product is reactive today: a customer emails, the AI replies. This phase adds a
-- PROACTIVE flow — when a tracked item reaches a milestone status, the platform emails the
-- customer without being asked. The "who do we notify" mapping does not exist anywhere
-- (port-service Containers have no owner), so the inbound enquiry itself is the subscription:
-- a STATUS_UPDATE ticket gives us (tenant, customer_email, identifier), recorded as a watch.

-- One proactive subscription: keep <customer_email> posted about <identifier> until DELIVERED.
-- last_notified_status holds the status the customer was last told about (seeded from the
-- reactive reply), so the poller never re-notifies a status it already sent.
CREATE TABLE shipment_watch (
    id                   BIGSERIAL    PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL REFERENCES tenant(id),
    identifier           VARCHAR(255) NOT NULL,
    customer_email       VARCHAR(255) NOT NULL,
    last_notified_status VARCHAR(50),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT now(),
    -- One subscription per (identifier, recipient) within a tenant: a customer re-asking
    -- about the same shipment updates the existing watch instead of duplicating it.
    UNIQUE (tenant_id, identifier, customer_email)
);

-- Same two-layer isolation as the V19/V20/V21 tables: RLS guards native SQL / raw JDBC,
-- Hibernate @TenantId guards entity queries. Grants arrive automatically via the
-- ALTER DEFAULT PRIVILEGES from V19 (this migration runs as the admin role).
ALTER TABLE shipment_watch ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_watch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shipment_watch
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

-- A proactive update is delivered as an outbound EmailTicket (so it shows in /tickets, is
-- audited, flows analytics and reuses the auto-send gate). This flag tells those generated
-- tickets apart from genuine inbound mail — analytics "received" counts must exclude them.
ALTER TABLE email_ticket ADD COLUMN proactive BOOLEAN NOT NULL DEFAULT FALSE;

-- feature.status-update-automation follows feature.auto-send: it emails real customers
-- without an operator, so both the seeded row AND the code default are OFF.
-- statusupdate.milestones: comma-separated ContainerStatus names that trigger a notification
-- (port-service returns the raw enum name). Intermediate steps stay silent to avoid spamming.
-- ai.status-update-prompt: drafting template for proactive notices; placeholders substituted
-- at runtime — {itemContext} (looked-up item data) and {previousStatus} (the prior status).
INSERT INTO app_setting (key, value) VALUES
    ('feature.status-update-automation', 'false'),
    ('statusupdate.milestones', 'AT_PORT,RELEASED,DELIVERED'),
    ('ai.status-update-prompt',
'You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
Write a short, proactive status update email to notify the customer that their shipment has progressed.
This is NOT a reply to a customer message — the customer has not just written to you. Open accordingly.
Use only the shipment information provided. Be helpful, polite and factual. Do NOT invent information.

Shipment information:
{itemContext}

Previous status: {previousStatus}

Write only the email body (no subject line). Keep it to a few sentences.')
ON CONFLICT (key) DO NOTHING;
