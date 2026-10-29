# 7. Keycloak is the only identity store

**Status:** accepted · 2026-06 · 2026-10 (first tenant user is ADMIN; names collected)

## Context
Users, passwords, password policy, brute-force protection and (later) MFA are security work we
should not rebuild. Every user must carry their tenant.

## Decision
Users live only in Keycloak, with a `tenant_id` attribute and a realm role
(`OPERATOR` ⊂ `ADMIN` ⊂ `SUPER_ADMIN`). The backend provisions them through the Admin REST API
(`KeycloakAdminService`, a thin WebClient rather than the admin-client library) using a
service-account client. Onboarding writes the `tenant` row and creates the first user — the
tenant's **ADMIN**, with first and last name — as the last step of one DB transaction.

## Consequences
- A Keycloak failure rolls back the tenant row: no orphan tenants.
- Keycloak 26's user profile drops unmanaged attributes unless the realm allows them
  (`unmanagedAttributePolicy=ADMIN_EDIT`), and it requires first and last name. Both are covered by
  an E2E test against a real Keycloak container.
- The operator's name signs their replies.
- A realm change needs a re-import on existing deployments.
