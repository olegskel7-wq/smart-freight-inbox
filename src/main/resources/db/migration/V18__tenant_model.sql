-- Phase 2.2 Session 1: tenant model.
-- Every tenant-owned table gets a tenant_id column; existing data is attributed to the
-- default tenant (id=1), which represents the current single-mailbox installation.
-- RLS policies and the dedicated app DB role live in V19.

CREATE TABLE tenant (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

INSERT INTO tenant (id, name) VALUES (1, 'Default');
-- The explicit id=1 insert bypasses the sequence; bump it so the next tenant gets id=2.
SELECT setval('tenant_id_seq', 1, true);

-- No DEFAULT is kept on tenant_id: after the backfill every insert must state its tenant
-- explicitly (Hibernate @TenantId / system code), so a missing tenant fails loudly
-- instead of silently landing in the default tenant.

ALTER TABLE email_ticket ADD COLUMN tenant_id BIGINT;
UPDATE email_ticket SET tenant_id = 1;
ALTER TABLE email_ticket
    ALTER COLUMN tenant_id SET NOT NULL,
    ADD CONSTRAINT fk_email_ticket_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

ALTER TABLE ticket_audit_log ADD COLUMN tenant_id BIGINT;
UPDATE ticket_audit_log SET tenant_id = 1;
ALTER TABLE ticket_audit_log
    ALTER COLUMN tenant_id SET NOT NULL,
    ADD CONSTRAINT fk_ticket_audit_log_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

ALTER TABLE carrier_rate ADD COLUMN tenant_id BIGINT;
UPDATE carrier_rate SET tenant_id = 1;
ALTER TABLE carrier_rate
    ALTER COLUMN tenant_id SET NOT NULL,
    ADD CONSTRAINT fk_carrier_rate_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

ALTER TABLE outbox_event ADD COLUMN tenant_id BIGINT;
UPDATE outbox_event SET tenant_id = 1;
ALTER TABLE outbox_event
    ALTER COLUMN tenant_id SET NOT NULL,
    ADD CONSTRAINT fk_outbox_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- app_user gets the tenant attribution but stays OUTSIDE RLS/@TenantId: it is the login
-- bootstrap table — at authentication time no tenant context exists yet. Email remains
-- globally unique (one login = one tenant, no tenant picker on the login form).
ALTER TABLE app_user ADD COLUMN tenant_id BIGINT;
UPDATE app_user SET tenant_id = 1;
ALTER TABLE app_user
    ALTER COLUMN tenant_id SET NOT NULL,
    ADD CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- Message-ID dedup becomes per-tenant: two tenants can legitimately receive a copy of
-- the same email (CC, forwarded chains) — that is not a duplicate.
ALTER TABLE email_ticket DROP CONSTRAINT email_ticket_message_id_key;
ALTER TABLE email_ticket ADD CONSTRAINT uq_email_ticket_tenant_message UNIQUE (tenant_id, message_id);

-- One rate per tenant + carrier + route (CSV re-upload upserts within the tenant).
DROP INDEX uq_carrier_rate_route;
CREATE UNIQUE INDEX uq_carrier_rate_route
    ON carrier_rate (tenant_id, lower(carrier), lower(origin), lower(destination));

CREATE INDEX idx_email_ticket_tenant     ON email_ticket (tenant_id) WHERE deleted = false;
CREATE INDEX idx_ticket_audit_log_tenant ON ticket_audit_log (tenant_id);
