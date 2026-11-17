# 8. React SPA + stateless JWT resource server

**Status:** accepted · 2026-06

## Context
The original Thymeleaf UI could not do live updates and tied the API to server-side sessions.
Future consumers (webhooks, CRM integrations, mobile) need an API, not pages.

## Decision
A **Vite + React + TypeScript SPA** served from `/` that logs in directly against Keycloak with PKCE
(access token in memory only, silent renew). `/api/**` is an OAuth2 resource server: stateless,
CSRF off. Live updates run STOMP over `/ws`, authenticated on CONNECT: after commit a JPA entity
listener pushes a small `{id, status}` event, and the SPA re-runs its current query.

## Consequences
- No server sessions, so auth does not stand in the way of horizontal scaling.
- Server-side DTOs compute everything derived (percentages, sell prices); the SPA does no business
  maths.
- Bulk JPQL updates (e.g. the SLA sweep) bypass the listener and are not pushed live.
