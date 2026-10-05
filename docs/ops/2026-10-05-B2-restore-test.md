# Restore test (Gate B2) — 2026-10-05 18:24 UTC

**Result: ✅ RESTORE PROVEN** · source: `offsite` · restore took 2s · throwaway `pgvector/pgvector:pg16`

Dumps:
- smartfreight: offsite vault:daily/smartfreight-20261005.sql.gz (decrypted)
- keycloak: offsite vault:daily/keycloak-20261005.sql.gz (decrypted)

| Check | Result |
|---|---|
| Flyway (numbered migrations) | 37 (34 applied, 0 failed) |
| pgvector extension | 0.8.2 |
| Tables with RLS forced | 13 |
| RLS as `smartfreight_app`, tenant set | ✅ tenant 1 sees its 122 of 148 tickets |
| RLS as `smartfreight_app`, no tenant | ✅ no tenant set → 0 rows |
| Keycloak realms | master, smartfreight |
| Keycloak users | 4 |

| Table | Restored rows | Live rows now | |
|---|---|---|---|
| app_setting | 24 | 24 | ✅ |
| carrier_rate | 8 | 8 | ✅ |
| email_ticket | 148 | 148 | ✅ |
| flyway_schema_history | 34 | 34 | ✅ |
| knowledge_doc | 0 | 0 | ✅ |
| outbox_event | 55 | 55 | ✅ |
| quote_follow_up | 4 | 4 | ✅ |
| shipment_watch | 8 | 8 | ✅ |
| tenant | 3 | 3 | ✅ |
| tenant_document | 6 | 6 | ✅ |
| tenant_mailbox | 4 | 4 | ✅ |
| tenant_setting | 4 | 4 | ✅ |
| ticket_audit_log | 26 | 26 | ✅ |
| training_example | 2 | 2 | ✅ |
| webhook_delivery | 0 | 0 | ✅ |
| webhook_subscription | 0 | 0 | ✅ |

## Notes (05 Oct 2026, dev machine)

- **Drill target:** `OFFSITE_LOCAL_DIR=/offsite-drill` (a docker volume) stood in for the S3 bucket — the
  same rclone crypt path the server uses. On disk the dumps were `*.sql.gz.bin` starting with the
  `RCLONE` crypt header (not gzip): unreadable without the passphrase.
- **Negative check:** the same run with a wrong `BACKUP_ENCRYPTION_PASSPHRASE` failed —
  `failed to authenticate decrypted block - bad password?` (exit 1).
- **Local copies** (`restore-test.sh local`) restored identically the same day.
- **Found before this:** the `postgres_backups` volume was **empty** — `@daily` fires at 00:00 UTC and
  the dev stack is never up at midnight, so no dump had ever been taken; and `keycloak-db` (operators)
  had no backup at all. Both fixed in the same change (`keycloak-backup` service).
- **Still to do on the VPS:** repeat with the real bucket (`restore-test.sh offsite`) — Gate B1 done
  only when that report is in this folder.
