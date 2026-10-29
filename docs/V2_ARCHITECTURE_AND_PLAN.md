> **Historical document** (last updated 2026-05-26). It describes an earlier stage of the system and is
> kept for context only. For how the system works today see [ARCHITECTURE.md](ARCHITECTURE.md) and
> [adr/](adr/README.md).

# Universal AI Email Automation Platform — Architecture & Roadmap

> **Single source of truth for the product roadmap.**  
> Phases are sequential; each phase builds on the previous one.  
> The current codebase is v1 (SmartFreight Inbox MVP) — it is refactored in place, not forked into a separate package.

---

## Current State (v1 — Complete)

A working MVP deployed as two microservices:

| Service | Port | Description |
|---------|------|-------------|
| `inbox` | 8080 | IMAP IDLE ingestion → AI pipeline → Thymeleaf UI |
| `port-service` | 9000 | Container data backend (freight-specific) |

**AI pipeline (synchronous):**
```
IMAP IDLE → EmailIngestionService → AiExtractionService (Qwen 2.5)
         → ContainerLookupService → AiDraftingService (Llama 3.1)
         → operator reviews in UI → SMTP reply
```

**Known limitations to fix:**
- Synchronous AI pipeline blocks IMAP thread
- Freight/container terminology hardcoded throughout
- No Circuit Breaker on inter-service calls
- Session-based auth doesn't scale horizontally
- Single tenant only

---

## Phase 0 — Universalisation (2–3 weeks) ← NEXT

**Goal:** Remove all freight-specific terminology so the platform can serve any sector (estate agents, transport, legal, NHS, etc.) without code changes.

### Changes
- `Container` entity → `TrackedItem`
- `port-service` → `item-service`
- `ContainerStatus` hardcoded enum → configurable statuses stored in DB per tenant
- `ContainerLookupService` → `ItemLookupService` interface with pluggable implementations
- AI prompts (extraction + drafting) → configurable per tenant in DB (not hardcoded strings)
- `extractedContainerNumber` field → `extractedIdentifier`

### Result
A single codebase that can be configured for any customer sector via DB settings — no redeploy required.

---

## Phase 1 — Critical Architecture Fixes (4–5 weeks)

**Goal:** Make the platform production-ready before adding tenants.

### 1.1 Async AI Pipeline (RabbitMQ)
The synchronous pipeline is the biggest architectural risk: one slow LLM call blocks the IMAP thread.

```
EmailIngestionService → RabbitMQ (ticket.created) → TicketProcessingConsumer
                                                  → dead-letter queue on failure
```

- At-least-once delivery with persistent messages
- Retry/backoff on transient LLM failures
- Dead-letter queue for permanently failed tickets
- Horizontal scaling of consumers independent of ingestion

### 1.2 Circuit Breaker (Resilience4j)
Wrap all external calls (Ollama, item-service):
- Circuit breaker: open at 50% failures over 10 calls
- Retry: 3 attempts with exponential backoff (2s → 4s → 8s)
- Bulkhead: max 4 concurrent Ollama calls

### 1.3 OAuth2/OIDC — Keycloak
Replace session-based form login with Keycloak:
- Operator UI: Authorization Code flow (browser-based)
- Inter-service (inbox → item-service): Client Credentials flow, replace API Key
- Scales horizontally without sticky sessions
- Ready for multi-tenant roles in Phase 2

### 1.4 Redis
- Session store (Keycloak tokens cache)
- Item/container lookup cache (reduce item-service calls)
- Rate limiting on AI endpoints
- Distributed lock (replace ShedLock with Redis-based locking)

### 1.5 Small fixes
- `findAll()` without pagination → `Page<>` with `Pageable`
- API versioning consolidated at `/api/v1/` everywhere

---

## Phase 2 — Multi-tenancy (5–6 weeks)

**Goal:** One deployment serves multiple customers (tenants), each fully isolated.

### Data isolation
- `tenant_id` column on all tenant-scoped tables (`email_ticket`, `tracked_item`, prompts, statuses)
- **PostgreSQL Row-Level Security (RLS)** — enforced at DB level, not just application layer
- Per-tenant Flyway schema or schema-per-tenant strategy

### Configuration per tenant
Each tenant configures independently via admin UI:
- Mailbox credentials (IMAP/SMTP)
- AI prompts (extraction + drafting)
- LLM endpoint (Ollama on-premise or cloud API)
- Tracked item statuses and their labels
- Notification settings

### Auth with tenant context
- Keycloak realm per tenant (or single realm with tenant claim in JWT)
- `TenantContext` (ThreadLocal) resolved from JWT claim on every request
- All JPA queries automatically scoped to current tenant via RLS

---

## Phase 3 — Infrastructure (parallel with Phase 2)

### Kubernetes
- Helm charts for inbox, item-service, RabbitMQ, Redis, Keycloak
- Horizontal Pod Autoscaler on `TicketProcessingConsumer`
- Secrets via Kubernetes Secrets + external-secrets operator

### Terraform
- UK cloud infrastructure as code (AWS London / Azure UK South)
- VPC, RDS (PostgreSQL), ElastiCache (Redis), EKS/AKS

### Observability stack
- **Loki** — log aggregation (replaces ad-hoc log reading)
- **Jaeger** — distributed tracing (end-to-end: email in → SMTP out)
- **Sentry** — error tracking with stack traces and alerts
- **OpenTelemetry** SDK wired into all services → exports to Jaeger

### CI/CD
- GitHub Actions → Blue-Green deployments
- Automated rollback on health check failure

---

## Phase 4 — Product (5–6 weeks)

### React/Next.js UI
Replace Thymeleaf with a modern SPA:
- Server-side rendering for initial load (Next.js)
- Real-time ticket updates via WebSocket
- Multi-tenant admin panel (per-tenant config, user management)
- SLA dashboard (avg processing time, failure rate, queue depth)

### Webhook API
Allow customers to integrate with their own CRMs:
```
POST /api/v1/webhooks
Events: ticket.created, ticket.draft_ready, ticket.replied, ticket.closed
```

### Self-service onboarding
- Tenant registration → Keycloak realm provisioned automatically
- Guided setup: connect mailbox → configure prompts → test with sample email
- Subscription management (Stripe integration)

---

## Phase 5 — Compliance (3–4 weeks)

### GDPR / ICO
- Encryption at rest (PostgreSQL Transparent Data Encryption or column-level)
- Data retention policy: auto-delete email bodies after configurable period
- Right to erasure: `DELETE /api/v1/tenants/{id}/data`
- Data Processing Agreement template in onboarding flow

### Audit log
- Immutable append-only log: who did what to which ticket and when
- Exportable by tenant admin (ICO compliance)

### Secrets management
- HashiCorp Vault for all credentials (DB passwords, SMTP, API keys)
- Dynamic secrets for DB (short-lived credentials, auto-rotated)

### Penetration testing
- OWASP ZAP automated scan in CI
- Annual external pentest before enterprise sales

---

## Target Architecture Diagram

```
                    ┌─────────────────────────────────────────┐
                    │         Tenant A / Tenant B / ...        │
                    │  (isolated by RLS + Keycloak realm)      │
                    └────────────┬────────────────────────────┘
                                 │
              ┌──────────────────▼──────────────────┐
              │           inbox service              │
              │                                     │
   IMAP ──────► EmailIngestionService               │
              │         │                           │
              │         ▼                           │
              │      RabbitMQ                       │
              │         │                           │
              │         ▼                           │
              │  TicketProcessingConsumer           │
              │    ├─ Resilience4j (CB + retry)    │
              │    ├─ AiExtractionService (Qwen)   │
              │    ├─ ItemLookupService             │──► item-service :9000
              │    └─ AiDraftingService (Llama)    │
              │         │                           │
              │         ▼                           │
              │      PostgreSQL (RLS)               │
              │      Redis (cache + locks)          │
              │         │                           │
              │         ▼                           │
              │   Next.js UI / Webhook API          │
              └──────────────────────────────────── ┘
                                 │
              ┌──────────────────▼──────────────────┐
              │            Keycloak                  │
              │   (OAuth2/OIDC, per-tenant roles)   │
              └─────────────────────────────────────┘
                                 │
              ┌──────────────────▼──────────────────┐
              │         Observability                │
              │   Loki + Jaeger + Sentry + Grafana  │
              └─────────────────────────────────────┘
```

---

## Phase Summary

| Phase | Focus | Duration | Prerequisite |
|-------|-------|----------|--------------|
| **0** | Universalisation (TrackedItem, configurable statuses + prompts) | 2–3 weeks | v1 complete ✅ |
| **1** | RabbitMQ + Resilience4j + Keycloak + Redis | 4–5 weeks | Phase 0 |
| **2** | Multi-tenancy (RLS, per-tenant config) | 5–6 weeks | Phase 1 |
| **3** | Kubernetes + Terraform + Loki/Jaeger/Sentry + CI/CD | parallel with 2 | Phase 1 |
| **4** | React/Next.js UI + Webhook API + self-service onboarding | 5–6 weeks | Phase 2 |
| **5** | GDPR/ICO + Audit log + Vault + Pentest | 3–4 weeks | Phase 4 |

---

## Technology Decisions Log

| Decision | Chosen | Rejected | Reason |
|----------|--------|----------|--------|
| Message broker | RabbitMQ | Kafka | Simpler ops, sufficient for expected volume |
| Email ingestion | IMAP IDLE (keep) | Microsoft Graph API | IMAP works for any provider, Graph is Microsoft-only |
| Frontend | React / Next.js | Angular | Larger ecosystem, SSR built-in, better hire market in UK |
| Identity | Keycloak | Auth0 / Cognito | Self-hosted, UK data residency, no per-MAU cost |
| DB isolation | PostgreSQL RLS | Schema-per-tenant | Simpler migrations, single connection pool |
| Tracing | Jaeger (via OpenTelemetry) | Zipkin | Better UI, CNCF standard |
| Secrets | HashiCorp Vault | AWS Secrets Manager | Cloud-agnostic, dynamic secrets |

---

*Last updated: 26 May 2026*
