-- Phase 3.3 S2: shared-queue assignment (multi-operator teams).
--
-- A tenant's tickets are a shared queue — every operator sees all of them. This adds an optional
-- owner so work can be claimed/divided: an operator self-claims a ticket, or a tenant admin assigns
-- it to a teammate. Assignment is manual (Oleg's pick — no auto round-robin) and orthogonal to the
-- ticket lifecycle (status), so it is a plain nullable column, not a new state.
--
-- The value is a Keycloak user UUID (the same id /api/users exposes), NOT a local FK: operators
-- live in Keycloak (Phase 2.3), so there is no users table to reference. A NULL assignee = unclaimed.
-- email_ticket already carries RLS (V19) + Hibernate @TenantId, so no new policy is needed.
ALTER TABLE email_ticket ADD COLUMN assignee_user_id VARCHAR(36);

-- Supports the "assigned to me" filter (WHERE assignee_user_id = :uuid). The "unassigned" filter
-- (IS NULL) matches the bulk of rows and is served fine by the tenant-scoped scan.
CREATE INDEX idx_email_ticket_assignee ON email_ticket (assignee_user_id);
