<!-- Gate B10 drill: synthetic tenant 90099 seeded with tickets, audit log, rate, setting, mailbox, a Keycloak operator and a cache key; demo tenants 1 and 90001 live alongside. A wrong-name run was refused first ("Name mismatch ... refusing", exit 1). After the run tenant 1's 2 operators and its Redis control key were still present. -->

# Erasure record — tenant 90099 "Erasure Test Ltd"

**Result: ✅ ERASED** · started 2026-10-05T18:48:33Z · finished 2026-10-05T18:49:40Z

| Store | Before | After |
|---|---|---|
| Postgres, this tenant | webhook_delivery=0 webhook_subscription=0 ticket_audit_log=2 training_example=0 quote_follow_up=0 shipment_watch=0 outbox_event=0 knowledge_doc=0 email_ticket=2 tenant_document=0 carrier_rate=1 tenant_setting=1 tenant_mailbox=1  | webhook_delivery=0 webhook_subscription=0 ticket_audit_log=0 training_example=0 quote_follow_up=0 shipment_watch=0 outbox_event=0 knowledge_doc=0 email_ticket=0 tenant_document=0 carrier_rate=0 tenant_setting=0 tenant_mailbox=0  |
| Tenant row | 1 | 0 |
| Keycloak users (tenant_id=90099) | 1 | 0 |
| Redis cache keys | 1 | 0 |
| **Other tenants (must be unchanged)** | webhook_delivery=0 webhook_subscription=0 ticket_audit_log=26 training_example=2 quote_follow_up=4 shipment_watch=8 outbox_event=55 knowledge_doc=0 email_ticket=148 tenant_document=6 carrier_rate=8 tenant_setting=4 tenant_mailbox=4  | webhook_delivery=0 webhook_subscription=0 ticket_audit_log=26 training_example=2 quote_follow_up=4 shipment_watch=8 outbox_event=55 knowledge_doc=0 email_ticket=148 tenant_document=6 carrier_rate=8 tenant_setting=4 tenant_mailbox=4  |

Not erased by this procedure (see docs/ops/data-flow.md): encrypted backups until rotation (local ≤ 6
months, offsite ≤ 200 days; ledger entry added — re-run after any restore) and the client's own mailbox.
