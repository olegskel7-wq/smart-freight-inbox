-- Microsoft 365 mailboxes over OAuth2 (XOAUTH2 on the existing IMAP/SMTP pipeline).
--
-- Exchange Online refuses basic auth for IMAP (since 2022) and turns SMTP AUTH basic off by
-- default at the end of 2026, so an Outlook/M365 client cannot connect with a password at all.
-- Such a mailbox now signs in app-only (client credentials): OUR multi-tenant Entra app — its
-- client id/secret are platform config (env MS365_CLIENT_ID / MS365_CLIENT_SECRET), never per row —
-- asks the CUSTOMER's directory for a token, which their admin enabled by granting consent and
-- giving the app's service principal access to exactly this mailbox. The row therefore stores no
-- secret, only WHICH directory to ask: ms_directory_id (tenant GUID or its verified domain).
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
