# Gate B6 — onboarding dry-run of a new tenant (29 Oct 2026)

**Result: ✅ full cycle passed on the fixed build** (branch `gate-b/b6-idle-date-threshold`).
Done once by hand, through the real UI at `https://localhost`, exactly as a client would be onboarded —
the demo GreenMail/Mailpit harness stood in for the client's mailbox and their customer.

## Run (second pass, fixed build)

| # | Step | Evidence |
|---|---|---|
| 0 | Pre-connection mail: an UNREAD email with INTERNALDATE 2025-10-29 placed in `ops@b6-dryrun.example.com` before the mailbox was connected | IMAP list: `2025-10-29T19:48:03Z seen=false` |
| 1 | SUPER_ADMIN → `/tenants` → create "B6 Dry Run Haulage Ltd" with a 14-char all-lowercase password | Clear 400: "Password does not meet the security policy…"; tenant list unchanged (no orphan row) |
| 2 | Same with a policy-compliant password | Tenant id **8**, 1 user; message "created with admin …" |
| 3 | First login as the new tenant's first user | Keycloak "Update Account Information" (first/last name) — see open point 1; role **ADMIN**, Admin section visible |
| 4 | `/settings` → add mailbox: IMAP `greenmail:3993`, SMTP `mailpit:1025` | Row id 9, status Starting… → Running; log `IMAP adapter started: mailbox 9 … for tenant 8` |
| 5 | IDLE first connect with the old unread mail present | **0 tickets** for tenant 8; the old mail still `seen=false` (untouched) |
| 6 | New customer email via SMTP → `ops@b6-dryrun.example.com` | Ticket **133** DRAFT_READY, STATUS_UPDATE, `extracted_identifier=MSCU1234567`, `mailbox_id=9`; draft grounded in port-service data, confidence 90 % |
| 7 | Operator sends the draft from the ticket page | Ticket CLOSED, audit `SEND`; Mailpit: `Re: Where is container MSCU1234567?` From `ops@b6-dryrun.example.com` To the customer |
| 8 | Isolation | The new tenant's inbox showed only its own ticket (0 before step 6, then 1); 91 tenant-1 tickets never visible |
| 9 | Teardown | `scripts/erase-tenant.sh` (see `erasure-ledger.md`) |

## Found by the first pass — fixed in this branch

1. **IDLE took ALL unread INBOX mail on first connect, however old** (noted under B5). Now
   `MailboxImapAdapterManager` gives the IDLE adapter a selector: only mail received since the mailbox
   was connected (`tenant_mailbox.created_at`, the same cut-off as `MailboxCatchUp`). Verified in the
   Spring Integration 7.0.4 bytecode that a rejected message is left as it was: the receiver fetches
   only ENVELOPE/CONTENT_INFO/FLAGS (no body → no implicit \Seen) and flags only the messages that pass
   the selector. Step 5 above is the live proof.
2. **The tenant's first user was created as OPERATOR** — a Phase 2.3 leftover that Phase 3.3 (ADMIN
   role, `/api/settings/**` ADMIN-only) never updated. A new tenant had no way to connect its mailbox or
   add users: onboarding dead-ended at step 4. Now `TenantManagementService` creates it as **ADMIN**.
3. **Onboarding password check lagged the realm policy**: min 8 in the form/service vs Keycloak
   `length(10)+upper+lower+digit`; a Keycloak rejection surfaced as a 500. Now min 10 (shared constant
   with `UserManagementService`) and a Keycloak 400 maps to the same clear policy message (step 1).

## Open points (not fixed here)

1. ✅ **RESOLVED 29 Oct (branch `fix/operator-names-at-provisioning`):** first/last name are now collected on `/tenants` and `/users` and sent to Keycloak, so the first login has no profile form and replies are signed with the operator's name (live: "Kind regards, Dana Price / Signature Check Haulage Ltd" in Mailpit; Keycloak E2E asserts no required action). Users created before keep the form once — correct, a nameless user signs "Customer Service Team". Was: **First login asks for first/last name** (Keycloak declarative user profile marks `firstName`/
   `lastName` required for `user`; provisioning sends neither). Harmless but it is the first thing a
   client's user sees. Options: make both optional in the realm user profile (realm import + a live
   `kcadm` update) or collect names at provisioning.
2. A deep link opened while logged out lands on `/tickets` after login (the SPA's `redirect_uri` is
   fixed) — minor.
3. Old unread mail is re-scanned (envelope fetch only) on each IDLE wake-up; fine for hundreds, worth a
   server-side `SEARCH SINCE` if a client's INBOX holds thousands of unread messages.
4. The cut-off is `created_at` minus a 15-minute clock-skew grace (added after code-review), so a mail
   server running behind cannot make IDLE + catch-up silently drop the first enquiries. Edit in place
   keeps `created_at`: re-pointing a never-used mailbox to a DIFFERENT account days later would
   ingest that account's unread mail since creation — needs a `connected_at` column (V38) to reset
   on a host/username change; narrow, deferred.
5. Still for the real pilot: a real client mailbox (Gmail App Password / M365 OAuth2 — the latter is
   not built yet) and B14 (demo mail OFF) before the first real email.
