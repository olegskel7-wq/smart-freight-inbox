# Smart Freight Inbox — presentation map & diagrams

**How to use:** copy **Mermaid** blocks into [mermaid.live](https://mermaid.live), Marp, Notion, or GitHub.  
For PowerPoint: export PNG/SVG from mermaid.live.

You can add a university logo and a “risks / metrics” slide next to the Observability block for your committee.

---

## 1. Flow diagrams (recommended for slides)

### A. End-to-end pipeline (v1 MVP)

Mail intake, persistence, synchronous LLM processing (after commit), operator handoff.

```mermaid
flowchart TB
  subgraph INGEST["1 — Email ingestion"]
    direction TB
    MS[(Mail server<br/>IMAP)]
    IDLE[IMAP IDLE<br/>Spring Integration]
    SVC[EmailIngestionService]
    DUP{Message-ID<br/>already in DB?}
    MS --> IDLE --> SVC --> DUP
    DUP -->|yes| SKIP[Skip duplicate]
    DUP -->|no| SAVE[Save EmailTicket<br/>status NEW]
  end

  subgraph PROC["2 — AI processing (synchronous, same thread)"]
    direction TB
    TPS[TicketProcessingService<br/>called in afterCommit]
    ST1[Set status PROCESSING]
    QW[Qwen 2.5 via Ollama<br/>extract container number]
    CL[Container lookup<br/>PostgreSQL]
    LL[Llama 3.1 via Ollama<br/>draft reply]
    ST2[Set status DRAFT_READY]
    SAVE --> TPS
    TPS --> ST1 --> QW --> CL --> LL --> ST2
  end

  subgraph DATA["3 — Data store"]
    PG[(PostgreSQL<br/>EmailTicket, Container, User)]
    ST1 -.-> PG
    QW -.-> PG
    CL --> PG
    LL -.-> PG
    ST2 --> PG
    SAVE --> PG
  end

  subgraph OPS["4 — Human-in-the-loop"]
    UI[Thymeleaf UI + REST API<br/>/tickets, /api/tickets]
    ST2 --> UI
  end

  subgraph RES["Resilience"]
    RL[RETRY_LATER on operator reject]
    FL[FAILED on errors]
    TPS -.-> FL
    UI -.-> RL
  end
```

### B. Simplified linear flow (single slide)

```mermaid
flowchart LR
  A[(IMAP mailbox)] --> B[IMAP IDLE]
  B --> C[Ingestion + dedup]
  C --> D[(PostgreSQL)]
  D --> E[Synchronous LLM pipeline]
  E --> F[Qwen extract]
  F --> G[DB lookup]
  G --> H[Llama draft]
  H --> D
  D --> I[Operator review]
```

### C. EmailTicket state machine

```mermaid
stateDiagram-v2
  [*] --> NEW : email ingested
  NEW --> PROCESSING : worker starts
  RETRY_LATER --> PROCESSING : scheduler retry
  PROCESSING --> DRAFT_READY : success
  PROCESSING --> FAILED : unrecoverable error
  DRAFT_READY --> DRAFT_READY : operator edits draft
  DRAFT_READY --> CLOSED : operator approve / save & close
  DRAFT_READY --> RETRY_LATER : operator reject
  CLOSED --> [*]
  FAILED --> [*] : manual resolution
```

### D. Runtime & observability (Docker stack)

```mermaid
flowchart TB
  subgraph APP["Application"]
    SB[Spring Boot backend :8080]
  end
  subgraph DATA["Data & AI"]
    PG[(PostgreSQL)]
    OL[Ollama + GPU<br/>Qwen / Llama]
  end
  subgraph OBS["Observability"]
    PR[Prometheus]
    GF[Grafana]
    AC[Actuator /health]
  end
  SB --> PG
  SB --> OL
  SB --> AC
  AC --> PR
  PR --> GF
```

### E. Planned v2 delta (context slide)

```mermaid
flowchart LR
  subgraph V1["v1 today"]
    IMAP[IMAP IDLE]
    SYNC[Synchronous pipeline]
  end
  subgraph V2["v2 target"]
    GRA[Microsoft Graph]
    RMQ[RabbitMQ]
    RD[(Redis)]
    MT[Multi-tenant]
  end
  IMAP -.->|replace| GRA
  SYNC -.->|replace| RMQ
  RMQ -.-> RD
  GRA -.-> RMQ
```

---

## 2. Mindmap (v1 + path to v2)

```mermaid
mindmap
  root((Smart Freight<br/>Inbox))
    Business
      Port logistics
      On-premise / GDPR
      Human-in-the-loop
    Ingestion
      IMAP IDLE v1
      Graph API v2
    Processing
      Message-ID deduplication
      Synchronous v1 / queue v2
      Ticket statuses
    Local AI
      Qwen extraction
      Llama drafting
      Ollama
    Data
      PostgreSQL
      Flyway migrations
    Security
      Session cookie (form login)
      Spring Security 6
      InMemoryUserDetailsManager (demo)
    Observability
      Actuator
      Prometheus / Grafana
```

---

## 3. Layered architecture (nested view)

*Not a data treemap by area; this is a **layer hierarchy** for an “architecture” slide.*

```mermaid
flowchart TB
  subgraph P["System layers"]
    subgraph L4["Layer 4 — Consumers"]
      U[Operator / UI]
    end
    subgraph L3["Layer 3 — API & security"]
      API[REST API]
      SEC[Form login + Session]
    end
    subgraph L2["Layer 2 — Domain & integrations"]
      ING[Email ingestion]
      PROC[Ticket processing]
      LLM[WebClient → Ollama]
    end
    subgraph L1["Layer 1 — Data & infrastructure"]
      PG[(PostgreSQL)]
      DC[Docker Compose]
      MON[Metrics / health]
    end
  end
  U --> API
  API --> SEC
  SEC --> ING
  SEC --> PROC
  ING --> PG
  PROC --> PG
  PROC --> LLM
  PG --> DC
  LLM --> DC
  MON --> DC
```

---

## 4. Data pipeline (compact slide)

```mermaid
flowchart LR
  M[IMAP mail] --> IDLE[IMAP IDLE]
  IDLE --> SVC[EmailIngestionService]
  SVC --> DB[(EmailTicket)]
  DB --> POOL[Synchronous LLM pipeline]
  POOL --> Q[Qwen extraction]
  Q --> C[Container lookup]
  C --> L[Llama draft]
  L --> DB
  DB --> OP[Operator]
```

---

## 5. Java package tree (v1 repository)

```
smartfreight.inbox
├── InboxApplication
├── actuator
│   └── OllamaHealthIndicator
├── config
│   ├── ImapConfig           ← Spring Integration, mailChannel
│   ├── SecurityConfig       ← form login + InMemoryUserDetailsManager
│   └── WebClientConfig      ← Ollama timeouts, connection pool
├── controller
│   ├── AuthController       ← POST /api/auth/login
│   ├── TicketController     ← REST /api/tickets (list/get/approve/reject)
│   ├── TicketViewController ← Thymeleaf UI
│   └── AdminController      ← POST /admin/reprocess (localhost only)
├── entity
│   ├── EmailTicket
│   ├── Container
│   ├── User
│   └── enums / TicketStatus
├── repository
│   ├── EmailTicketRepository
│   ├── ContainerRepository
│   └── UserRepository
├── integration
│   └── EmailIngestionService  ← schedules process() via afterCommit
├── service
│   ├── AuthService
│   ├── AiExtractionService
│   ├── AiDraftingService
│   ├── ContainerLookupService
│   └── TicketProcessingService  ← synchronous
├── scheduler
│   └── RetryScheduler
└── exception
    ├── GlobalExceptionHandler
    └── ContainerNotFoundException
```

---

## 6. Database schema (ER diagram)

Three tables, created by Flyway V1. `email_ticket` is the core entity; `container` is the reference catalogue; `app_user` holds operator credentials.

```mermaid
erDiagram
  email_ticket {
    bigserial   id                         PK
    varchar     message_id                 "UNIQUE, RFC-2822"
    varchar     sender_email
    varchar     subject
    text        body
    varchar     extracted_container_number FK
    varchar     status                     "NEW|PROCESSING|DRAFT_READY|RETRY_LATER|FAILED|CLOSED"
    text        draft_reply
    integer     retry_count                "default 0"
    boolean     deleted                    "soft-delete, default false"
    integer     version                    "optimistic lock"
    timestamp   created_at
    timestamp   updated_at
  }

  container {
    bigserial   id               PK
    varchar     container_number "UNIQUE, ISO 6346"
    varchar     status
    timestamp   eta
    varchar     location
  }

  app_user {
    bigserial   id       PK
    varchar     email    "UNIQUE"
    varchar     password "BCrypt"
    varchar     role
  }

  email_ticket ||--o| container : "extracted_container_number → container_number"
```

### Flyway migration history

| Version | File | Purpose |
|---------|------|---------|
| V1 | `V1__init.sql` | Create all tables + indexes |
| V2 | `V2__seed_demo_containers.sql` | Seed test containers (OOLU, MSCU…) |
| V3 | `V3__fix_demo_user_password.sql` | Fix BCrypt hash for demo operator |
| V4 | `V4__seed_test_tickets.sql` | Seed sample tickets for demo |

### Indexes

| Index | Table | Column | Condition |
|-------|-------|--------|-----------|
| `idx_email_ticket_message_id` | email_ticket | message_id | — |
| `idx_email_ticket_status` | email_ticket | status | `WHERE deleted = false` |
| `idx_container_number` | container | container_number | — |

---

## 7. Repository root (top-level files)

```
inbox/
├── pom.xml
├── docker-compose.yml       ← backend, postgres, ollama, prometheus, grafana
├── README.md
├── docs/                    ← project documentation (this file et al.)
├── monitoring/
│   └── prometheus.yml
├── scripts/
│   ├── entrypoint.sh
│   ├── github-push.ps1
│   └── verify-imap-ready.ps1
└── src/main/
    ├── java/.../com/smartfreight/inbox/...
    └── resources/
        ├── application.yaml
        ├── templates/       ← Thymeleaf (login, tickets, ticket-detail)
        └── db/migration/    ← V1..V4
```

---

## 8. Roadmap v1 → v2 (timeline slide)

```mermaid
timeline
    title Product evolution
    section v1 MVP
      Spring Boot monolith : IMAP IDLE : PostgreSQL : Ollama
    section v2
      Multi-tenant : RabbitMQ : Redis : Microsoft Graph
      Resilience4j : OpenTelemetry : horizontal scaling
```

---

*Extend with committee-specific slides (demo, risks, test strategy) as needed.*
