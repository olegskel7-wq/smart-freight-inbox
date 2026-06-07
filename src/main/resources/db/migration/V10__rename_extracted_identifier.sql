-- Phase 0 (universalisation): neutralise the extracted-key column name on email_ticket.
-- The pipeline now works with a vertical-neutral "identifier" (TrackedItem.identifier) rather
-- than a freight-specific container number, so rename the column to match.
ALTER TABLE email_ticket RENAME COLUMN extracted_container_number TO extracted_identifier;
