-- Phase 2.2 debt close-out: per-tenant outbound SMTP.
--
-- Until now every tenant's replies went out through the single platform SMTP account
-- (env smtp.*). A tenant_mailbox row already models "this tenant's email account" for
-- inbound (IMAP); these columns add its outbound half so replies can be sent FROM the
-- tenant's own address. All nullable: a row with no smtp_host is inbound-only and the
-- sender falls back to the platform account (same override shape as ollama.base-url).
--
-- The SMTP password reuses the existing password_enc (AES-256-GCM) — one account,
-- one credential, as Gmail/O365 app passwords work. No new secret column, no RLS change:
-- the V21 tenant_isolation policy already covers every column of this table.

ALTER TABLE tenant_mailbox
    ADD COLUMN smtp_host    VARCHAR(255),
    ADD COLUMN smtp_port    INT,
    ADD COLUMN from_address VARCHAR(255);
