# 4. Multi-tenancy: Hibernate @TenantId plus Postgres RLS

**Status:** accepted · 2026-06

## Context
One database serves several client companies. A single missed `WHERE tenant_id = ?` would leak one
client's email to another — the worst possible bug for this product.

## Decision
Two independent layers. **Hibernate `@TenantId`** filters entity queries and stamps inserts.
**Postgres RLS** policies (FORCE) read `app.tenant_id` / `app.is_system`, which
`TenantAwareDataSource` sets on every connection checkout; they also cover native SQL and raw JDBC.
The application connects as `smartfreight_app` (NOSUPERUSER, NOBYPASSRLS); only Flyway uses admin
credentials. `TenantContext` carries the tenant: from the JWT for requests, from the message for the
pipeline, SYSTEM for schedulers. An unset context fails loudly.

## Consequences
- A query bug in one layer is caught by the other; isolation tests run on real Postgres.
- The context must wrap the whole transaction (`TransactionTemplate` inside `runAs…`).
- Every new business table needs `tenant_id`, an RLS policy, `@TenantId` and an entry in
  `scripts/erase-tenant.sh`.
- Per-tenant erasure is a scripted, verified operation (`erase-tenant.sh`).
