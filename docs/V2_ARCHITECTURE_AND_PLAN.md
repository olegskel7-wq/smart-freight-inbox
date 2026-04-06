# Smart Freight Inbox — Version 2 (full architecture & implementation plan)

> **Purpose of this document:** single source of truth for v2. It does not prescribe changes to v1 code here.  
> **Team decision:** v2 uses **RabbitMQ** (not Kafka). Anywhere the original plan said “RabbitMQ or Kafka”, this project means **RabbitMQ**.

---

## 1. Purpose of v2

Version 2 is a **production-grade, scalable, multi-tenant** evolution of Smart Freight Inbox.  
It extends v1 with:

- **RabbitMQ** message queues
- **Redis** (cache, rate limiting, distributed locks)
- **Microsoft Graph API** instead of IMAP for email ingestion
- **Multi-tenant** architecture
- **Resilience** (circuit breaker, retry, bulkhead)
- **SLA monitoring**
- **Horizontal scaling**
- A full **DevOps** pipeline

**Development boundaries:**

- **v1 = MVP** (already implemented) — do not break it without an explicit task.
- **v2 = scale-out extension** — implement separately (e.g. package/module `v2`).

---

## 2. Full technology stack (v2)

### Backend

- Java 17
- Spring Boot 3 (when starting v2, align with the actual Spring Boot version in the repository)
- Spring Security 6 (JWT + refresh tokens)
- Spring Data JPA
- PostgreSQL
- Redis (cache, rate limiting, distributed locks)
- **RabbitMQ** (message broker)
- Microsoft Graph API (email ingestion)
- Resilience (circuit breaker, retry, bulkhead)
- Micrometer + Prometheus + Grafana
- OpenTelemetry (distributed tracing)

### AI

- Ollama (on-premise)
- Qwen 2.5 — extraction
- Llama 3.1 — drafting
- Embeddings for semantic search (optional)

### Frontend

- Angular 17
- Multi-tenant UI
- Role-based access control

### DevOps

- Docker Compose
- Kubernetes (optional)
- CI/CD pipeline
- Secrets management (HashiCorp Vault / Azure Key Vault)

---

## 3. High-level architecture (v2)

```
Microsoft Graph API → Email Ingestion Service
                           │
                           ▼
                         RabbitMQ
                           │
                           ▼
                 Ticket Processing Worker
        ┌──────────────┬───────────────┬───────────────┐
        ▼              ▼               ▼
  Extraction (Qwen)   Container Lookup   Drafting (Llama)
        │              │               │
        └──────────────┴───────────────┴───────────────┘
                           ▼
                     PostgreSQL + Redis
                           ▼
                        Operator UI
```

---

## 4. Major changes from v1 → v2

1. **IMAP → Microsoft Graph API**  
   Webhooks and delta queries; no mailbox polling; avoids IMAP IDLE reconnect issues.

2. **In-process async → RabbitMQ pipeline**  
   At-least-once delivery, retry/backoff, dead-letter queues, horizontal scaling of consumers.

3. **Redis**  
   Container caching, rate limiting, distributed locks, refresh-token storage.

4. **Multi-tenancy**  
   `tenant_id` on tenant-scoped tables, row-level filtering, tenant-aware JWT claims, tenant-scoped cache keys.

5. **Resilience**  
   Circuit breaker around Ollama calls, retry with backoff, bulkhead isolation for LLM workloads.

6. **Observability**  
   OpenTelemetry traces, Prometheus metrics, Grafana dashboards.

---

## 5. Backend module layout (v2)

```
src/main/java/com/smartfreight/inbox/v2
│
├── config/
│   ├── TenantConfig.java
│   ├── RedisConfig.java
│   ├── RabbitConfig.java
│   ├── GraphApiConfig.java
│   ├── ResilienceConfig.java
│   ├── OpenTelemetryConfig.java
│
├── ingestion/
│   ├── GraphWebhookController.java
│   ├── GraphDeltaService.java
│   ├── EmailNormalizationService.java
│
├── queue/
│   ├── TicketProducer.java
│   ├── TicketConsumer.java
│   ├── DeadLetterHandler.java
│
├── processing/
│   ├── ExtractionWorker.java
│   ├── DraftingWorker.java
│   ├── ContainerLookupWorker.java
│
├── tenant/
│   ├── TenantResolver.java
│   ├── TenantContext.java
│   ├── TenantFilter.java
│
├── security/
│   ├── JwtTenantFilter.java
│   ├── RefreshTokenService.java
│
└── util/
    ├── RateLimiter.java
    ├── DistributedLock.java
```

---

## 6. Implementation phases

### Phase 1 — Messaging layer

- **RabbitMQ** configuration  
- `TicketProducer`, `TicketConsumer`, dead-letter queue handling

### Phase 2 — Microsoft Graph API

- `GraphWebhookController`, `GraphDeltaService`, `EmailNormalizationService`

### Phase 3 — Redis layer

- `RedisConfig`, `DistributedLock`, `RateLimiter`, Redis-backed refresh tokens

### Phase 4 — Multi-tenancy

- `TenantContext`, `TenantResolver`, `TenantFilter`, tenant-aware JPA queries

### Phase 5 — Resilience4j

- Circuit breaker for Ollama, retry policies, bulkhead configuration

### Phase 6 — Observability

- OpenTelemetry wiring, traces across queue → worker → database, Prometheus & Grafana

### Phase 7 — UI

- Multi-tenant login, tenant switching, SLA-oriented dashboards

---

## 7. Instructions for Cursor / AI agents (summary)

- This document defines **Smart Freight Inbox v2**.
- **v1 is implemented** — do not modify v1 code unless there is a separate, explicit task.
- Implement **v2 in isolation** following the package structure above.
- Email ingestion: **Microsoft Graph API**.
- Messaging: **RabbitMQ**.
- **Redis**: caching, rate limiting, locks.
- **Multi-tenant**: `tenant_id`, filtering, JWT claims.
- **Resilience**: circuit breaker, retry, bulkhead.
- **OpenTelemetry** + **Prometheus/Grafana**.

Before generating code, confirm with the project owner **which phase or package to start from**.

---

*This file is the canonical v2 plan stored in the repository.*
