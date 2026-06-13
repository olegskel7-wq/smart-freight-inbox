-- Phase 2.2 Session 3: per-tenant mailboxes.
--
-- Each row is one IMAP inbox the platform watches for a tenant. The application builds
-- a dynamic IMAP IDLE adapter per enabled row (MailboxReconciler); mail arriving on an
-- adapter is ingested inside that tenant's context — replacing the single env-configured
-- global mailbox, which MailboxBootstrap imports as the Default tenant's first row.
--
-- password_enc is AES-256-GCM ciphertext (SecretCipher, key from MAILBOX_ENCRYPTION_KEY):
-- the daily pg_dump backups must not contain client mailbox credentials in plaintext.

CREATE TABLE tenant_mailbox (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant(id),
    host         VARCHAR(255) NOT NULL,
    port         INT          NOT NULL DEFAULT 993,
    username     VARCHAR(255) NOT NULL,
    password_enc TEXT         NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    -- The same account watched twice would double-ingest every message (dedup is
    -- per-tenant, so it would NOT absorb a second adapter for the same tenant).
    UNIQUE (tenant_id, host, username)
);

-- Same two-layer isolation as the V19/V20 tables: RLS guards native SQL / raw JDBC,
-- Hibernate @TenantId guards entity queries. Grants arrive automatically via the
-- ALTER DEFAULT PRIVILEGES from V19 (this migration runs as the admin role).
ALTER TABLE tenant_mailbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_mailbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_mailbox
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );
