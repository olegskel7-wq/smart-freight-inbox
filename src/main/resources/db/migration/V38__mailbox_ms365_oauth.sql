-- Microsoft 365 mailboxes over OAuth2 (XOAUTH2 on the existing IMAP/SMTP pipeline).
--
-- Exchange Online refuses basic auth for IMAP (since 2022) and turns SMTP AUTH basic off by
-- default at the end of 2026, so an Outlook/M365 client cannot connect with a password at all.
-- Such a mailbox now signs in app-only (client credentials): OUR multi-tenant Entra app — its
-- client id/secret are platform config (env MS365_CLIENT_ID / MS365_CLIENT_SECRET), never per row —
-- asks the CUSTOMER's directory for a token, which their admin enabled by granting consent and
-- giving the app's service principal access to exactly this mailbox. The row therefore stores no
-- secret, only WHICH directory to ask: ms_directory_id (the directory GUID, bound to the tenant below).
--
-- password_enc becomes nullable: an MS365_OAUTH row has no password. The CHECK keeps each auth
-- type's required field present, so a half-configured row is unrepresentable. Existing rows get
-- the PASSWORD default — no data change, no re-encryption.
ALTER TABLE tenant_mailbox
    ADD COLUMN auth_type       VARCHAR(20) NOT NULL DEFAULT 'PASSWORD',
    ADD COLUMN ms_directory_id VARCHAR(255),
    ALTER COLUMN password_enc DROP NOT NULL,
    ADD CONSTRAINT ck_tenant_mailbox_auth CHECK (
        (auth_type = 'PASSWORD' AND password_enc IS NOT NULL)
        OR (auth_type = 'MS365_OAUTH' AND ms_directory_id IS NOT NULL)
    );

-- V37 gap: shipment_watch got the composite mailbox FK without an index, so every mailbox delete
-- (ON DELETE SET NULL lookup) seq-scans shipment_watch. Mirrors idx_email_ticket_mailbox.
CREATE INDEX idx_shipment_watch_mailbox ON shipment_watch (tenant_id, mailbox_id)
    WHERE mailbox_id IS NOT NULL;

-- Which Microsoft directory belongs to which SmartFreight tenant. A directory id is NOT a secret
-- (anyone can look it up from a domain), and our one multi-tenant app gets a token for ANY directory
-- that consented to it — so a typed-in id would let tenant A point a mailbox at customer B's
-- consented directory and read B's mail. A row here is created only by the consent flow
-- (Ms365ConsentService): an administrator of the directory signs in to Microsoft from a link issued
-- to this tenant, and the directory id is taken from the ID token Microsoft returns to us — never
-- from the browser. UNIQUE(directory_id) across ALL tenants: one customer directory, one tenant.
CREATE TABLE tenant_ms365_directory (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant(id),
    directory_id VARCHAR(255) NOT NULL,
    -- The signed-in administrator (UPN / e-mail from the ID token) — who connected it, for support.
    bound_by     VARCHAR(320),
    bound_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_ms365_directory UNIQUE (directory_id),
    CONSTRAINT uq_ms365_directory_tenant UNIQUE (tenant_id, directory_id)
);

-- Same two-layer isolation as the other tenant tables (RLS + Hibernate @TenantId). The UNIQUE above
-- is checked against every row regardless of RLS, which is exactly the cross-tenant guarantee.
ALTER TABLE tenant_ms365_directory ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_ms365_directory FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_ms365_directory
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

-- An MS365_OAUTH mailbox may only name a directory bound to ITS OWN tenant — composite FK, like the
-- V37 mailbox links, so the cross-tenant case is unrepresentable rather than merely validated.
-- RESTRICT: a directory cannot be unbound while a mailbox still signs in through it.
ALTER TABLE tenant_mailbox
    ADD CONSTRAINT fk_tenant_mailbox_ms365_directory
        FOREIGN KEY (tenant_id, ms_directory_id)
        REFERENCES tenant_ms365_directory (tenant_id, directory_id);
