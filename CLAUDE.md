# SmartFreight → Universal AI Email Automation Platform
## CLAUDE.md — AI Assistant Context

---

## 👤 Project Owner
- **Name:** Oleg
- **Level:** Junior/Mid Java developer (recently defended thesis project)
- **Goal:** Turn the thesis project into a commercial SaaS product for the UK market
- **Approach:** Developing solo with Claude

---

## 📦 What This Project Is

**Current name:** SmartFreight Inbox  
**Future name:** Universal AI Email Automation Platform

A system that automatically processes incoming customer email enquiries:
1. Receives email via IMAP IDLE
2. Extracts a key identifier via AI (Qwen 2.5)
3. Looks up data in a backend service
4. Generates a reply draft via AI (Llama 3.1)
5. Operator reviews and sends via SMTP

---

## 🏗️ Architecture (current state)

### Two microservices:
```
inbox (port 8080)        — main application
port-service (port 9000) — container data backend (to be universalised)
```

### Tech Stack:
```
Java 17 (plan: migrate to Java 25)
Spring Boot 4.0.5
PostgreSQL 16 (port-service: port 5435, inbox: separate DB)
Flyway (migrations)
Ollama — local LLM (Qwen 2.5:7b + Llama 3.1:8b)
Spring Security (session-based + API Key for inter-service communication)
Spring Integration (IMAP IDLE)
Micrometer + Prometheus + Grafana
ShedLock (distributed scheduler locking)
Testcontainers (integration tests)
Thymeleaf + Bootstrap 5 (UI — plan: replace with React)
Docker Compose
```

### inbox structure (35 Java files):
```
config/     — SmtpConfig, ImapConfig, WebClientConfig, SecurityConfig
service/    — AiExtractionService, AiDraftingService, ContainerLookupService,
              TicketProcessingService, EmailReplyService
controller/ — TicketViewController, AdminController
integration/— EmailIngestionService
scheduler/  — RetryScheduler
health/     — OllamaHealthIndicator
entity/     — EmailTicket, User, Container, TicketStatus
repository/ — EmailTicketRepository, UserRepository
exception/  — GlobalExceptionHandler, ContainerNotFoundException
```

### port-service structure (8 Java files):
```
PortServiceApplication, ContainerController, ContainerService,
ContainerRepository, Container (entity), ContainerResponse (DTO),
ContainerStatus (enum), StatusUpdateScheduler, ShedLockConfig, ApiKeyFilter, SecurityConfig
```

---

## 🔑 Key Architectural Decisions

1. **IMAP IDLE** instead of polling — push notifications, instant reaction
2. **Two LLM models** — Qwen 2.5 for extraction (accuracy), Llama 3.1 for drafting (text quality)
3. **Local LLM (Ollama)** — data never leaves UK servers, GDPR by design
4. **Human-in-the-loop** — operator approves every reply
5. **API Key auth** with `MessageDigest.isEqual()` (timing-safe) between services
6. **ShedLock** — protection against parallel scheduler execution across multiple instances
7. **Optimistic locking + soft delete** on EmailTicket
8. **Message-ID deduplication** — protection against duplicates on IMAP reconnect

---

## 🚀 Product Strategy

### Positioning:
**"The only AI email automation that keeps your data on UK servers"**

### UK target verticals (priority order):
1. **Estate Agents** — fast sales cycle, many repetitive emails, owner decides alone
2. **Lorry/Transport companies** — a potential client exists right now!
3. **Legal/Solicitors** — high ticket size, GDPR critical, long sales cycle
4. **NHS/Healthcare** — huge market, strict compliance, very long sales cycle
5. **Finance/Insurance** — high ticket size, FCA regulated

### Competitive advantage:
- Intercom, Zendesk, Freshdesk — store data in the US
- Our product — 100% UK infrastructure
- NHS, Legal, Finance cannot use competitors

---

## 📋 Enterprise Roadmap

### Phase 0 — Universalisation (2–3 weeks) ← NEXT STEP
- [ ] Container → TrackedItem, port-service → item-service
- [ ] Configurable statuses (from DB, not hardcoded enum)
- [ ] Configurable AI prompts per tenant
- [ ] Abstract ItemLookupService interface

### Phase 1 — Critical Architectural Fixes (4–5 weeks)
- [ ] Async AI Pipeline (RabbitMQ) — synchronous pipeline is the main problem!
- [ ] Circuit Breaker (Resilience4j)
- [ ] OAuth2/OIDC (Keycloak)
- [ ] Redis (session store + cache + rate limiting)
- [ ] Fix findAll() → pagination
- [ ] API versioning /api/v1/

### Phase 2 — Multi-tenancy (5–6 weeks)
- [ ] Tenant model in DB
- [ ] Row-Level Security (PostgreSQL RLS)
- [ ] Per-tenant configuration (mailbox, prompts, LLM endpoint)

### Phase 3 — Infrastructure (parallel with Phase 2)
- [ ] Kubernetes
- [ ] Terraform
- [ ] Loki + Jaeger + Sentry
- [ ] CI/CD Blue-Green

### Phase 4 — Product (5–6 weeks)
- [ ] React/Next.js instead of Thymeleaf
- [ ] WebSocket real-time
- [ ] Analytics dashboard
- [ ] Webhook API for CRM integrations
- [ ] Self-service onboarding

### Phase 5 — Compliance (3–4 weeks)
- [ ] GDPR/ICO — encryption at rest, data retention, right to erasure
- [ ] Audit log
- [ ] HashiCorp Vault
- [ ] Penetration testing

---

## 👥 Clients

### Potential Client #1
- **Sector:** Lorry/Transport company (UK)
- **Status:** Potential — Oleg's contact
- **Need:** Automated replies to delivery status enquiries
- **Integration complexity:** Medium (need to find out if they have a TMS/GPS system)
- **Next step:** Run a discovery call → after that create `clients/lorry-company/` structure

---

## 🖥️ Infrastructure (current — development)

```
PostgreSQL: localhost:5435 (port-service), separate port for inbox
Ollama: localhost:11434
IntelliJ IDEA 2025.3.4
Java 17 (plan: Java 25)
Docker Compose for the full stack
```

### Run Configuration (port-service):
```
PORT_SERVICE_API_KEY=smartfreight-port-secret
DB_HOST=localhost
DB_PORT=5435
DB_USER=postgres
DB_PASSWORD=postgres
SCHEDULER_INTERVAL_MS=300000
VM: -Djava.rmi.server.hostname=127.0.0.1 -Djava.net.preferIPv4Stack=true
```

---

## ⚠️ Known Issues

1. **Slow port-service startup** (~20 sec to banner) — large SB4.x classpath
   - Tried: lazy-initialization, DevTools, Windows Defender exclusions
   - Solution: migrate to Java 25
2. **Synchronous AI pipeline** — main architectural problem
3. **No Circuit Breaker** on inter-service calls
4. **Session-based auth** does not scale horizontally
5. **findAll() without pagination** in StatusUpdateScheduler

---

## 📁 File Locations

```
C:\Users\olegs\Downloads\inbox\inbox\               — monorepo (git), main application
C:\Users\olegs\Downloads\inbox\inbox\port-service\  — data microservice (subfolder of monorepo)
```
> ⚠️ Previously port-service was a separate folder `Downloads\port-service` — deleted 25.05.2026.
> The only copy is now in the monorepo. Open exactly this in IntelliJ.

---

## 💡 How to Work with Oleg

- Oleg sets the direction and identifies problem areas
- Claude implements and explains
- After important decisions — update this file
- Communication language: English (this file) / Russian acceptable in conversation
- Style: direct, no fluff

### ⚠️ AT THE END OF EACH SESSION — git push
- Before finishing, Claude commits + pushes changes to GitHub
- Repository: `smart-freight-inbox` (monorepo: inbox + port-service in subfolder)
- docker-compose builds port-service from `./port-service` — this is the git/deploy copy
- Commit message: short and to the point

---

*Last updated: 26 May 2026*
