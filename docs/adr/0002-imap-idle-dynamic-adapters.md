# 2. IMAP IDLE with adapters built from the database

**Status:** accepted · 2026-06 (dynamic adapters) · 2026-10 (date threshold, catch-up)

## Context
Polling adds latency and load. Each tenant brings its own mailboxes, and onboarding must not need a
restart. The IDLE adapter is at-most-once (it flags a message before our handler persists it) and
only ever sees unread mail.

## Decision
One Spring Integration **IMAP IDLE** adapter per enabled `tenant_mailbox` row, registered at runtime
(`MailboxImapAdapterManager`) and reconciled with the DB every 30 s (`MailboxReconciler`). Ingestion
runs inside the mailbox's tenant and never throws back into the adapter. IDLE takes only mail
received since the mailbox was connected (a selector; older mail is left untouched).
`MailboxCatchUp` re-scans the last 48 h read-only on every health probe and ingests whatever has no
ticket yet.

## Consequences
- Connecting, editing or disabling a mailbox takes effect within ~30 s.
- Ingestion becomes at-least-once by reconciliation; Message-ID dedup (unique per tenant) absorbs
  duplicates.
- A client's old unread backlog never floods the inbox at onboarding.
- Mailbox health is probed independently (`MailboxHealthMonitor`, alert `MailboxDown`).
