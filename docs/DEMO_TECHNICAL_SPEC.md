# Smart Freight Inbox — Demo Technical Specification

> **Purpose:** Reference document for further development and demo preparation.
> Describes the simplified v1 architecture (sync, Thymeleaf), service interactions, and demo script.

---

## 1. Simplified v1 Architecture (Post-Simplification)

### What changed from original v1

| Component | Before | After (demo) |
|-----------|--------|--------------|
| `TicketProcessingService.process()` | `@Async` — runs in separate thread pool | Synchronous — runs in calling thread |
| `AsyncConfig.java` | Defines `llmTaskExecutor` thread pool, MDC decorator, rejection handler | **Deleted** |
| `application.yaml` | `spring.task.scheduling.pool.size: 10` | Removed (no thread pool needed) |
| Frontend | Angular 17 (planned, not built) | Thymeleaf server-side HTML |
| Auth UI | `POST /api/auth/login` → JWT → localStorage | `POST /login` → session attribute |

### What stays the same

- IMAP IDLE email ingestion (`EmailIngestionService`)
- Full AI pipeline: Qwen 2.5 extraction → container lookup → Llama 3.1 drafting
- All entities: `EmailTicket`, `Container`, `User`, `TicketStatus`
- PostgreSQL + Flyway migrations
- Spring Security + JWT (for REST API)
- `RetryScheduler` (runs every 2 minutes, retries `RETRY_LATER` tickets)
- `OllamaHealthIndicator`, Actuator, Prometheus, Grafana

---

## 2. Full Technology Stack

### Backend

| Technology | Version | Role |
|------------|---------|------|
| Java | 17 | Language |
| Spring Boot | 4.0.5 | Application framework |
| Spring MVC | (Boot-managed) | REST controllers + Thymeleaf MVC |
| Spring Data JPA | (Boot-managed) | Repository layer |
| Spring Security | (Boot-managed) | JWT filter + session auth |
| Spring Integration | (Boot-managed) | IMAP IDLE mail channel |
| Spring Scheduling | (Boot-managed) | `RetryScheduler` |
| Flyway | (Boot-managed) | DB schema migrations |
| Lombok | (Boot-managed) | Boilerplate reduction |
| jjwt | 0.12.5 | JWT generation and validation |

### Database

| Technology | Version | Role |
|------------|---------|------|
| PostgreSQL | 16 (Docker) | Main relational store |
| HikariCP | (Boot-managed) | Connection pool |

### AI / ML

| Technology | Version | Role |
|------------|---------|------|
| Ollama | 0.3.14 (Docker) | Local LLM serving (on-premise) |
| Qwen 2.5 | 7B parameter | Container number extraction from email text |
| Llama 3.1 | 8B parameter | Professional freight reply draft generation |
| WebClient (WebFlux) | (Boot-managed) | Reactive HTTP client for Ollama API calls |

### Frontend (v1 simplified)

| Technology | Role |
|------------|------|
| Thymeleaf | Server-side HTML templating |
| Bootstrap 5 (CDN) | Styling, no npm required |
| HTML forms | User interaction (no JavaScript needed) |

### Observability

| Technology | Version | Role |
|------------|---------|------|
| Spring Actuator | (Boot-managed) | Health endpoints, metrics |
| Micrometer | (Boot-managed) | Metrics collection |
| Prometheus | 2.53.0 (Docker) | Metrics storage |
| Grafana | 11.1.0 (Docker) | Metrics visualization |

### Infrastructure

| Technology | Role |
|------------|------|
| Docker Compose | Single-command local deployment |
| NVIDIA GPU (RTX 5080) | Ollama inference acceleration |
| `entrypoint.sh` | Auto-pulls Qwen + Llama models on first start |

---

## 3. Service Interaction Map

### 3.1 Email Ingestion Flow (synchronous after simplification)

```
Mail Server (IMAP)
      │
      │  IMAP IDLE — persistent connection, no polling
      ▼
ImapConfig → mailChannel (Spring Integration MessageChannel)
      │
      │  @ServiceActivator — Spring calls this automatically on new message
      ▼
EmailIngestionService.processIncomingEmail(MimeMessage)
      │
      ├─► Extract Message-ID from headers
      ├─► Deduplication: EmailTicketRepository.findByMessageId()
      │     └─ if duplicate → return (skip)
      ├─► Parse MIME body (plain text or multipart)
      ├─► Extract sender email from From: header
      ├─► EmailTicketRepository.save(ticket) → status = NEW
      │
      │  SYNCHRONOUS call (no @Async after simplification)
      ▼
TicketProcessingService.process(ticketId)
      │
      ├─► Load ticket from DB
      ├─► Guard: skip if status != NEW and != RETRY_LATER
      ├─► Set status = PROCESSING → saveAndFlush
      │
      ├─► Step 1: AiExtractionService.extractContainerNumber(body)
      │     ├─► POST http://ollama:11434/api/generate {model: "qwen2.5:7b"}
      │     └─► On failure: regex fallback (ISO 6346 pattern [A-Z]{4}\d{7})
      │
      ├─► Step 2: ContainerLookupService.findByNumber(containerNumber)
      │     └─► ContainerRepository.findByContainerNumber(number)
      │
      ├─► Step 3: AiDraftingService.generateDraft(body, container)
      │     ├─► Build prompt: email body + container status/location/ETA
      │     ├─► POST http://ollama:11434/api/generate {model: "llama3.1:8b"}
      │     └─► On failure: static fallback draft text
      │
      └─► Set draftReply, status = DRAFT_READY → save
```

### 3.2 Authentication Flow (Web UI)

```
Browser → GET /login
      │
      ▼
TicketViewController → return "login" (Thymeleaf template)
      │
      ▼
Browser → POST /login {email, password}
      │
      ▼
TicketViewController.handleLogin()
      │
      ├─► AuthService.login(email, password)
      │     ├─► UserRepository.findByEmail(email)
      │     ├─► BCryptPasswordEncoder.matches(raw, hash)
      │     ├─► JwtService.generateToken(email)
      │     └─► return token
      │
      ├─► Store token in HttpSession attribute "jwt"
      └─► redirect:/tickets
```

### 3.3 Authentication Flow (REST API)

```
Client → POST /api/auth/login {email, password}
      │
      ▼
AuthController
      │
      ▼
AuthService.login(email, password)
      ├─► UserRepository.findByEmail(email)
      ├─► BCryptPasswordEncoder.matches(raw, hash)
      ├─► JwtService.generateToken(email)
      └─► return LoginResponse{token}
      │
      ▼
Client stores JWT → uses in Authorization: Bearer <token> header
```

### 3.4 Ticket List and Detail (Web UI)

```
Browser → GET /tickets (with session jwt)
      │
      ▼
TicketViewController.listTickets(session)
      ├─► Validate session jwt (JwtService.isTokenValid)
      │     └─ if invalid → redirect:/login
      ├─► EmailTicketRepository.findAll() or findByStatus(filter)
      └─► model.addAttribute("tickets", tickets) → "tickets" template

Browser → GET /tickets/{id}
      │
      ▼
TicketViewController.ticketDetail(id, session)
      ├─► Validate session
      ├─► EmailTicketRepository.findById(id)
      └─► model.addAttribute("ticket", ticket) → "ticket-detail" template

Browser → POST /tickets/{id}/approve
      │
      ▼
TicketViewController.approveTicket(id, session)
      ├─► Validate session
      ├─► EmailTicketRepository.findById(id)
      ├─► ticket.setStatus(CLOSED)
      ├─► emailTicketRepository.save(ticket)
      └─► redirect:/tickets

Browser → POST /tickets/{id}/reject
      │
      ▼
TicketViewController.rejectTicket(id, session)
      ├─► Validate session
      ├─► ticket.setStatus(RETRY_LATER)
      ├─► emailTicketRepository.save(ticket)
      └─► redirect:/tickets
```

### 3.5 JWT Filter (REST API requests)

```
Any request to /api/** (except /api/auth/**)
      │
      ▼
JwtAuthenticationFilter.doFilterInternal()
      ├─► Read "Authorization" header
      ├─► Extract token after "Bearer "
      ├─► JwtService.extractUsername(token) → email
      ├─► JwtService.isTokenValid(token, email)
      │     ├─ valid → set SecurityContextHolder authentication
      │     └─ invalid → continue without auth (Spring returns 401)
      └─► filterChain.doFilter()
```

### 3.6 RetryScheduler

```
Every 2 minutes (fixedDelay = 120_000, initialDelay = 60_000)
      │
      ▼
RetryScheduler.retryFailedTickets()
      ├─► EmailTicketRepository.findByStatusAndRetryCountLessThan(RETRY_LATER, 5)
      ├─► For each ticket:
      │     ├─► ticket.retryCount++
      │     ├─► ticket.status = NEW
      │     ├─► emailTicketRepository.save(ticket)
      │     └─► TicketProcessingService.process(ticket.getId())  ← synchronous
      └─► Log results
```

### 3.7 Observability Chain

```
Spring Boot App
      │
      ├─► /actuator/health
      │     └─ OllamaHealthIndicator: GET http://ollama:11434/api/tags
      │
      └─► /actuator/prometheus (metrics endpoint)
              │
              ▼
        Prometheus (scrapes every 15s)
              │
              ▼
          Grafana (reads from Prometheus datasource)
                └─► Dashboard: tickets by status, retry count, Ollama health
```

---

## 4. Ticket Status State Machine

```
             ┌────────────────────────────────────────────────────┐
             │                                                    │
[email arrives]                                                  │
             │                                                    │
             ▼                                                    │
            NEW                                                   │
             │                                                    │
             │ TicketProcessingService.process()                  │
             ▼                                                    │
        PROCESSING                                                │
             │                                                    │
    ┌────────┼──────────┐                                         │
    │        │          │                                         │
    ▼        ▼          ▼                                         │
DRAFT_READY FAILED  RETRY_LATER ─────────────────────────────────┘
    │                               RetryScheduler sets NEW
    │
    ├─► operator approves → CLOSED
    └─► operator rejects  → RETRY_LATER
```

---

## 5. File Structure (after simplification)

```
inbox/
├── pom.xml                              (+ thymeleaf dependency)
├── docker-compose.yml                   (unchanged)
├── docs/
│   ├── DEMO_TECHNICAL_SPEC.md          ← this file
│   ├── MASTER_ROADMAP.md
│   ├── MASTER_ROADMAP_LT.md
│   ├── MERMAID_DIAGRAMS.md
│   ├── PROJECT_OVERVIEW.md
│   └── V2_ARCHITECTURE_AND_PLAN.md
├── monitoring/
│   ├── prometheus.yml
│   └── grafana/
│       ├── provisioning/
│       │   ├── datasources/prometheus.yml
│       │   └── dashboards/dashboard.yml
│       └── dashboards/smartfreight.json
└── src/main/
    ├── java/com/smartfreight/inbox/
    │   ├── InboxApplication.java
    │   ├── actuator/OllamaHealthIndicator.java
    │   ├── config/
    │   │   ├── ImapConfig.java
    │   │   ├── SecurityConfig.java          (+ session config for Thymeleaf)
    │   │   └── WebClientConfig.java
    │   │   # AsyncConfig.java DELETED
    │   ├── controller/
    │   │   ├── AuthController.java          (new — REST API login)
    │   │   ├── TicketController.java        (new — REST API tickets)
    │   │   └── TicketViewController.java   (new — Thymeleaf UI)
    │   ├── entity/
    │   │   ├── EmailTicket.java
    │   │   ├── Container.java
    │   │   ├── User.java
    │   │   └── enums/TicketStatus.java      (+ CLOSED value)
    │   ├── repository/
    │   │   ├── EmailTicketRepository.java
    │   │   ├── ContainerRepository.java
    │   │   └── UserRepository.java
    │   ├── integration/EmailIngestionService.java
    │   ├── service/
    │   │   ├── AuthService.java             (new)
    │   │   ├── AiExtractionService.java
    │   │   ├── AiDraftingService.java
    │   │   ├── ContainerLookupService.java
    │   │   └── TicketProcessingService.java  (@Async REMOVED)
    │   ├── scheduler/RetryScheduler.java
    │   ├── security/
    │   │   ├── JwtService.java              (+ generateToken() added)
    │   │   └── JwtAuthenticationFilter.java
    │   └── exception/
    │       ├── GlobalExceptionHandler.java
    │       └── ContainerNotFoundException.java
    └── resources/
        ├── application.yaml
        ├── templates/                       (new — Thymeleaf)
        │   ├── login.html
        │   ├── tickets.html
        │   ├── ticket-detail.html
        │   └── layout.html
        └── db/migration/
            ├── V1__init.sql
            └── V2__seed_demo_containers.sql
```

---

## 6. Demo Script (University Presentation)

### Setup (before presenting)

```bash
# Start all services
docker compose up --build

# Wait for: "Started InboxApplication" in logs
# Verify Ollama has models (takes ~2 min on first run):
docker logs ollama | grep "model loaded"
```

### Demo sequence (in front of committee)

**Step 1 — Open the app**
```
Browser → http://localhost:8080/login
```
Show: clean login form, no JavaScript complexity

**Step 2 — Login**
```
Email:    operator@smartfreight.com
Password: demo123
```
Show: redirects to ticket list (empty on fresh start)

**Step 3 — Trigger email ingestion**

Send a test email to the configured IMAP inbox with content like:
```
Subject: Container Status Request
Body:    Please advise on the status of container MSCU1234567.
         Expected at Port of Riga, shipment #PL-2025-0099.
```

Show: in Spring Boot logs — `EmailIngestionService` picks it up instantly via IMAP IDLE

**Step 4 — Refresh ticket list**
```
Browser → http://localhost:8080/tickets
```
Show:
- New ticket appears with status `DRAFT_READY`
- Subject, sender, creation time visible
- Status badge with colour

**Step 5 — Open ticket detail**
```
Click ticket row
```
Show:
- Original email body displayed
- Extracted container number: `MSCU1234567`
- AI-generated draft reply (Llama 3.1 output)
- Container details from DB: location, status, ETA

**Step 6 — Approve the draft**
```
Click "Approve" button
```
Show:
- Redirects back to list
- Ticket status changed to `CLOSED`

**Step 7 — Show observability (optional)**
```
Grafana  → http://localhost:3000  (admin / changeme)
Actuator → http://localhost:8080/actuator/health
```
Show: Ollama health indicator, ticket metrics

---

## 7. Key Ports Reference

| Service | URL | Credentials |
|---------|-----|-------------|
| Web UI (Thymeleaf) | http://localhost:8080/login | operator@smartfreight.com / demo123 |
| REST API | http://localhost:8080/api/ | JWT Bearer token |
| Actuator health | http://localhost:8080/actuator/health | public |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / changeme |
| PostgreSQL | localhost:5433 | postgres / postgres |
| Ollama API | http://localhost:11434 | — |

---

## 8. Ollama API Contract

### Extraction call (Qwen 2.5)

```http
POST http://ollama:11434/api/generate
Content-Type: application/json

{
  "model": "qwen2.5:7b",
  "prompt": "You are a logistics assistant. Extract the shipping container number...\n\nEmail:\n<email body>",
  "stream": false
}
```

Response:
```json
{
  "response": "MSCU1234567",
  "done": true
}
```

Timeout: 150 seconds (configured in `WebClientConfig`)

### Drafting call (Llama 3.1)

```http
POST http://ollama:11434/api/generate
Content-Type: application/json

{
  "model": "llama3.1:8b",
  "prompt": "You are a professional freight operations assistant...\n\nCustomer email:\n<body>\n\nContainer details:\nNumber: MSCU1234567\nStatus: In Transit\nLocation: Hamburg Port\nETA: 15 Apr 2025, 14:30",
  "stream": false
}
```

Timeout: 150 seconds

### Health check

```http
GET http://ollama:11434/api/tags
```

Used by `OllamaHealthIndicator` — if this returns 200, Ollama is healthy.

---

## 9. Database Schema (v1)

```sql
-- Users
CREATE TABLE app_user (
    id       BIGSERIAL    PRIMARY KEY,
    email    VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,           -- BCrypt hash
    role     VARCHAR(50)
);

-- Containers (logistics data)
CREATE TABLE container (
    id               BIGSERIAL    PRIMARY KEY,
    container_number VARCHAR(50)  NOT NULL UNIQUE,   -- ISO 6346: MSCU1234567
    status           VARCHAR(100),                   -- "In Transit", "At Port", etc.
    eta              TIMESTAMP,
    location         VARCHAR(255)
);

-- Email Tickets
CREATE TABLE email_ticket (
    id                         BIGSERIAL    PRIMARY KEY,
    message_id                 VARCHAR(255) NOT NULL UNIQUE,  -- RFC 2822 Message-ID
    sender_email               VARCHAR(255),
    subject                    VARCHAR(500),
    body                       TEXT,
    extracted_container_number VARCHAR(50),
    status                     VARCHAR(50)  NOT NULL DEFAULT 'NEW',
    draft_reply                TEXT,
    retry_count                INTEGER      NOT NULL DEFAULT 0,
    deleted                    BOOLEAN      NOT NULL DEFAULT false,  -- soft delete
    version                    INTEGER      NOT NULL DEFAULT 0,      -- optimistic lock
    created_at                 TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMP    NOT NULL DEFAULT now()
);
```

### Demo seed data (V2__seed_demo_containers.sql)

Pre-loaded containers:
- `MSCU1234567` — In Transit, ETA 15 Apr 2025, Hamburg Port
- `HLCU9876543` — At Port of Destination, Rotterdam
- `EVGU1111111` — Customs Clearance, Port of Riga

Pre-loaded user:
- `operator@smartfreight.com` / `demo123` (BCrypt hash)

---

*This file is the technical reference for demo preparation and further v1 development.*
*Synchronous simplification: AsyncConfig deleted, @Async removed from TicketProcessingService.*
*Frontend: Thymeleaf (not Angular) for demo simplicity.*
