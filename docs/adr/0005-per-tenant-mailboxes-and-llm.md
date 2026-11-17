# 5. Per-tenant mailboxes, SMTP and LLM endpoints

**Status:** accepted · 2026-06

## Context
Each client receives mail in its own accounts and must reply from them. Some clients may run their
own GPU. Database backups must not contain usable mail credentials.

## Decision
`tenant_mailbox` models one account: IMAP in, optional SMTP out. Passwords are AES-256-GCM encrypted
(`SecretCipher`, key `MAILBOX_ENCRYPTION_KEY`, also kept off the server). `MailSenderProvider` builds
and caches one sender per mailbox; a tenant without SMTP falls back to the platform account. A reply
leaves through the mailbox the email came in on (`email_ticket.mailbox_id`). `OllamaClientProvider`
keeps one client **and one circuit breaker** per LLM endpoint.

## Consequences
- One tenant's dead Ollama cannot park another tenant's tickets.
- Rotating a password is an in-place edit. Re-pointing a mailbox to another server requires the
  password again, so an admin cannot redirect a stored credential to a server of their own.
- The TLS certificates of client mail servers are verified (`MailTlsPolicy`).
