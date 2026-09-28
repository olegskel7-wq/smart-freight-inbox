-- Reply through the inbox the enquiry arrived on (multi-inbox fix).
--
-- Since Phase 3.3 a tenant can watch several tenant_mailbox rows, but MailSenderProvider sent every
-- reply through the tenant's OLDEST enabled SMTP mailbox — mail received on inbox B was answered
-- FROM inbox A. The ingesting mailbox is now recorded on the ticket, and replies resolve their
-- sender from it (falling back to the old rule, then the platform account, when it is unusable).
--
-- shipment_watch gets the same column: a proactive status update derives from the STATUS_UPDATE
-- ticket that armed the watch, but is sent long after it, so the watch carries the mailbox forward.
-- (Quote follow-ups need no column — quote_follow_up.ticket_id already points at the source ticket.)
--
-- Both columns are nullable: pre-V37 rows, dev-seed tickets and tickets whose mailbox was deleted
-- have none and use the fallback.
--
-- The FK is COMPOSITE (tenant_id, mailbox_id) → tenant_mailbox (tenant_id, id): a plain mailbox_id
-- FK would happily accept another tenant's mailbox id, whereas this one makes a cross-tenant link
-- unrepresentable in the schema itself (defence in depth under @TenantId + RLS). ON DELETE SET NULL
-- (mailbox_id) — PG15+ column list — clears ONLY mailbox_id when a mailbox is deleted (settings
-- delete, credential rotation = delete + re-add, demo harness teardown); a bare SET NULL would also
-- null the NOT NULL tenant_id and fail the delete.
ALTER TABLE tenant_mailbox
    ADD CONSTRAINT uq_tenant_mailbox_tenant_id_id UNIQUE (tenant_id, id);

ALTER TABLE email_ticket
    ADD COLUMN mailbox_id BIGINT,
    ADD CONSTRAINT fk_email_ticket_mailbox FOREIGN KEY (tenant_id, mailbox_id)
        REFERENCES tenant_mailbox (tenant_id, id) ON DELETE SET NULL (mailbox_id);

-- Serves the ON DELETE SET NULL lookup — without it every mailbox delete seq-scans email_ticket.
CREATE INDEX idx_email_ticket_mailbox ON email_ticket (tenant_id, mailbox_id)
    WHERE mailbox_id IS NOT NULL;

ALTER TABLE shipment_watch
    ADD COLUMN mailbox_id BIGINT,
    ADD CONSTRAINT fk_shipment_watch_mailbox FOREIGN KEY (tenant_id, mailbox_id)
        REFERENCES tenant_mailbox (tenant_id, id) ON DELETE SET NULL (mailbox_id);
