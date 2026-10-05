# Erasure ledger (Gate B10)

Every tenant erased with `scripts/erase-tenant.sh` is appended here automatically. Encrypted backups
keep erased data until rotation (local ≤ 6 months, offsite ≤ 200 days): **after restoring ANY backup,
re-run the erasure for every entry below whose date is later than the backup's.**

date | started (UTC) | tenant | result
---|---|---|---
2026-10-05 | 2026-10-05T18:48:33Z | tenant 90099 "Erasure Test Ltd" (synthetic — B10 drill, docs/ops/2026-10-05-B10-erasure-drill.md) | erased
